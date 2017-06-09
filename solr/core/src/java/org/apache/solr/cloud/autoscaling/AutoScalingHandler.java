/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableSet;
import org.apache.solr.api.Api;
import org.apache.solr.api.ApiBag;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.SolrClientDataProvider;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.CommandOperation;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.handler.RequestHandlerUtils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.TimeSource;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.cloud.ZkStateReader.SOLR_AUTOSCALING_CONF_PATH;
import static org.apache.solr.common.params.CommonParams.JSON;
import static org.apache.solr.common.params.AutoScalingParams.*;

/**
 * Handler for /cluster/autoscaling
 */
public class AutoScalingHandler extends RequestHandlerBase implements PermissionNameProvider {
  public static final String HANDLER_PATH = "/admin/autoscaling";
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected final CoreContainer container;
  private final List<Map<String, String>> DEFAULT_ACTIONS = new ArrayList<>(3);
  private static ImmutableSet<String> singletonCommands = ImmutableSet.of("set-cluster-preferences", "set-cluster-policy");
  private static final TimeSource timeSource = TimeSource.CURRENT_TIME;


  public AutoScalingHandler(CoreContainer container) {
    this.container = container;
    Map<String, String> map = new HashMap<>(2);
    map.put(NAME, "compute_plan");
    map.put(CLASS, "solr.ComputePlanAction");
    DEFAULT_ACTIONS.add(map);
    map = new HashMap<>(2);
    map.put(NAME, "execute_plan");
    map.put(CLASS, "solr.ExecutePlanAction");
    DEFAULT_ACTIONS.add(map);
    map = new HashMap<>(2);
    map.put(NAME, "log_plan");
    map.put(CLASS, "solr.LogPlanAction");
    DEFAULT_ACTIONS.add(map);
    map = new HashMap<>(2);
    map.put(NAME, "auto_add_replicas_plan");
    map.put(CLASS, "solr.AutoAddReplicasPlanAction");
    DEFAULT_ACTIONS.add(map);
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    try {
      String httpMethod = (String) req.getContext().get("httpMethod");
      RequestHandlerUtils.setWt(req, JSON);

      if ("GET".equals(httpMethod)) {
        String path = (String) req.getContext().get("path");
        if (path == null) path = "/cluster/autoscaling";
        List<String> parts = StrUtils.splitSmart(path, '/');
        if (parts.get(0).isEmpty()) parts.remove(0);

        if (parts.size() < 2 || parts.size() > 3) {
          // invalid
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown path: " + path);
        }

        Map<String, Object> map = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
        if (parts.size() == 2)  {
          rsp.getValues().addAll(map);
        } else if (parts.size() == 3 && DIAGNOSTICS.equals(parts.get(2))) {
          handleDiagnostics(rsp, map);
        }
      } else {
        if (req.getContentStreams() == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No commands specified for autoscaling");
        }
        List<CommandOperation> ops = CommandOperation.readCommands(req.getContentStreams(), rsp.getValues(), singletonCommands);
        if (ops == null) {
          // errors have already been added to the response so there's nothing left to do
          return;
        }
        for (CommandOperation op : ops) {
          switch (op.name) {
            case CMD_SET_TRIGGER:
              handleSetTrigger(req, rsp, op);
              break;
            case CMD_REMOVE_TRIGGER:
              handleRemoveTrigger(req, rsp, op);
              break;
            case CMD_SET_LISTENER:
              handleSetListener(req, rsp, op);
              break;
            case CMD_REMOVE_LISTENER:
              handleRemoveListener(req, rsp, op);
              break;
            case CMD_SUSPEND_TRIGGER:
              handleSuspendTrigger(req, rsp, op);
              break;
            case CMD_RESUME_TRIGGER:
              handleResumeTrigger(req, rsp, op);
              break;
            case CMD_SET_POLICY:
              handleSetPolicies(req, rsp, op);
              break;
            case CMD_REMOVE_POLICY:
              handleRemovePolicy(req, rsp, op);
              break;
            case CMD_SET_CLUSTER_PREFERENCES:
              handleSetClusterPreferences(req, rsp, op);
              break;
            case CMD_SET_CLUSTER_POLICY:
              handleSetClusterPolicy(req, rsp, op);
              break;
            default:
              throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown command: " + op.name);
          }
        }
      }
    } catch (Exception e) {
      rsp.getValues().add("result", "failure");
      throw e;
    } finally {
      RequestHandlerUtils.addExperimentalFormatWarning(rsp);
    }
  }

  private void handleDiagnostics(SolrQueryResponse rsp, Map<String, Object> autoScalingConf) throws IOException {
    Policy policy = new Policy(autoScalingConf);
    try (CloudSolrClient build = new CloudSolrClient.Builder()
        .withHttpClient(container.getUpdateShardHandler().getHttpClient())
        .withZkHost(container.getZkController().getZkServerAddress()).build()) {
      Policy.Session session = policy.createSession(new SolrClientDataProvider(build));
      List<Row> sorted = session.getSorted();
      List<Clause.Violation> violations = session.getViolations();

      List<Preference> clusterPreferences = policy.getClusterPreferences();

      List<Map<String, Object>> sortedNodes = new ArrayList<>(sorted.size());
      for (Row row : sorted) {
        Map<String, Object> map = Utils.makeMap("node", row.node);
        for (Cell cell : row.cells) {
          for (Preference clusterPreference : clusterPreferences) {
            Policy.SortParam name = clusterPreference.name;
            if (cell.name.equalsIgnoreCase(name.name())) {
              map.put(name.name(), cell.val);
              break;
            }
          }
        }
        sortedNodes.add(map);
      }

      Map<String, Object> map = new HashMap<>(2);
      map.put("sortedNodes", sortedNodes);

      map.put("violations", violations);
      rsp.getValues().add("diagnostics", map);
    }
  }

  private void handleSetClusterPolicy(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException, IOException {
    List clusterPolicy = (List) op.getCommandData();
    if (clusterPolicy == null || !(clusterPolicy instanceof List)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "A list of cluster policies was not found");
    }
    zkSetClusterPolicy(container.getZkController().getZkStateReader(), clusterPolicy);
    rsp.getValues().add("result", "success");
  }

  private void handleSetClusterPreferences(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException, IOException {
    List preferences = (List) op.getCommandData();
    if (preferences == null || !(preferences instanceof List)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "A list of cluster preferences not found");
    }
    zkSetPreferences(container.getZkController().getZkStateReader(), preferences);
    rsp.getValues().add("result", "success");
  }

  private void handleRemovePolicy(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException, IOException {
    String policyName = (String) op.getCommandData();

    if (policyName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The policy name cannot be empty");
    }
    Map<String, Object> autoScalingConf = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
    Map<String, Object> policies = (Map<String, Object>) autoScalingConf.get("policies");
    if (policies == null || !policies.containsKey(policyName)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No policy exists with name: " + policyName);
    }

    zkSetPolicies(container.getZkController().getZkStateReader(), policyName, null);
    rsp.getValues().add("result", "success");
  }

  private void handleSetPolicies(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException, IOException {
    Map<String, Object> policies = op.getDataMap();
    for (Map.Entry<String, Object> policy : policies.entrySet()) {
      String policyName = policy.getKey();
      if (policyName == null || policyName.trim().length() == 0) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The policy name cannot be null or empty");
      }
    }

    zkSetPolicies(container.getZkController().getZkStateReader(), null, policies);

    rsp.getValues().add("result", "success");
  }

  private void handleResumeTrigger(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException {
    String triggerName = op.getStr(NAME);

    if (triggerName == null || triggerName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The trigger name cannot be null or empty");
    }
    Map<String, Object> autoScalingConf = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
    Map<String, Object> triggers = (Map<String, Object>) autoScalingConf.get("triggers");
    Set<String> changed = new HashSet<>();
    if (triggers == null) {
      if (Policy.EACH.equals(triggerName)) {
        // no harm no foul
      } else {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No trigger exists with name: " + triggerName);
      }
    } else {
      if (!Policy.EACH.equals(triggerName) && !triggers.containsKey(triggerName)) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No trigger exists with name: " + triggerName);
      }
      for (Map.Entry<String, Object> entry : triggers.entrySet()) {
        if (Policy.EACH.equals(triggerName) || triggerName.equals(entry.getKey())) {
          Map<String, Object> triggerProps = (Map<String, Object>) entry.getValue();
          Boolean enabled = (Boolean)triggerProps.get(ENABLED);
          if (enabled != null && !enabled) {
            triggerProps.put(ENABLED, true);
            zkSetTrigger(container.getZkController().getZkStateReader(), entry.getKey(), triggerProps);
            changed.add(entry.getKey());
          }
        }
      }
    }
    rsp.getValues().add("changed", changed);
    rsp.getValues().add("result", "success");
  }

  private void handleSuspendTrigger(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException {
    String triggerName = op.getStr(NAME);

    if (triggerName == null || triggerName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The trigger name cannot be null or empty");
    }

    String timeout = op.getStr(TIMEOUT, null);
    Date resumeTime = null;
    if (timeout != null) {
      try {
        int timeoutSeconds = parseHumanTime(timeout);
        resumeTime = new Date(TimeUnit.MILLISECONDS.convert(timeSource.getTime(), TimeUnit.NANOSECONDS)
            + TimeUnit.MILLISECONDS.convert(timeoutSeconds, TimeUnit.SECONDS));
      } catch (IllegalArgumentException e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Invalid 'timeout' value for suspend trigger: " + triggerName);
      }
    }

    Map<String, Object> autoScalingConf = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
    Map<String, Object> triggers = (Map<String, Object>) autoScalingConf.get("triggers");
    Set<String> changed = new HashSet<>();

    if (triggers == null) {
      if (Policy.EACH.equals(triggerName)) {
      // no harm no foul
      } else {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No trigger exists with name: " + triggerName);
      }
    } else {
      if (!Policy.EACH.equals(triggerName) && !triggers.containsKey(triggerName)) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No trigger exists with name: " + triggerName);
      }
      for (Map.Entry<String, Object> entry : triggers.entrySet()) {
        if (Policy.EACH.equals(triggerName) || triggerName.equals(entry.getKey())) {
          Map<String, Object> triggerProps = (Map<String, Object>) entry.getValue();
          Boolean enabled = (Boolean)triggerProps.get(ENABLED);
          if (enabled == null || enabled) {
            triggerProps.put(ENABLED, false);
            if (resumeTime != null) {
              triggerProps.put(RESUME_AT, resumeTime.getTime());
            }
            zkSetTrigger(container.getZkController().getZkStateReader(), entry.getKey(), triggerProps);
            changed.add(entry.getKey());
          }
        }
      }
    }
    rsp.getValues().add("changed", changed);
    rsp.getValues().add("result", "success");
  }

  private void handleRemoveListener(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException {
    String listenerName = op.getStr(NAME);

    if (listenerName == null || listenerName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The listener name cannot be null or empty");
    }
    Map<String, Object> autoScalingConf = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
    Map<String, Object> listeners = (Map<String, Object>) autoScalingConf.get("listeners");
    if (listeners == null || !listeners.containsKey(listenerName)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No listener exists with name: " + listenerName);
    }
    zkSetListener(container.getZkController().getZkStateReader(), listenerName, null);
    rsp.getValues().add("result", "success");
  }

  private void handleSetListener(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException {
    String listenerName = op.getStr(NAME);
    String triggerName = op.getStr(TRIGGER);
    List<String> stageNames = op.getStrs(STAGE, Collections.emptyList());
    String listenerClass = op.getStr(CLASS);
    List<String> beforeActions = op.getStrs(BEFORE_ACTION, Collections.emptyList());
    List<String> afterActions = op.getStrs(AFTER_ACTION, Collections.emptyList());

    if (listenerName == null || listenerName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The listener name cannot be null or empty");
    }

    Map<String, Object> autoScalingConf = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
    Map<String, Object> triggers = (Map<String, Object>) autoScalingConf.get("triggers");
    if (triggers == null || !triggers.containsKey(triggerName)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "A trigger with the name " + triggerName + " does not exist");
    }
    Map<String, Object> triggerProps = (Map<String, Object>) triggers.get(triggerName);

    if (stageNames.isEmpty() && beforeActions.isEmpty() && afterActions.isEmpty()) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Either 'stage' or 'beforeAction' or 'afterAction' must be specified");
    }

    for (String stage : stageNames) {
      try {
        AutoScaling.TriggerStage.valueOf(stage);
      } catch (IllegalArgumentException e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Invalid stage name: " + stage);
      }
    }

    if (listenerClass == null || listenerClass.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The 'class' of the listener cannot be null or empty");
    }
    // validate that we can load the listener class
    // todo nocommit -- what about MemClassLoader?
    try {
      container.getResourceLoader().findClass(listenerClass, AutoScaling.TriggerListener.class);
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Listener not found: " + listenerClass, e);
    }

    List<Map<String, String>> actions = (List<Map<String, String>>) triggerProps.get("actions");
    Set<String> actionNames = new HashSet<>();
    actionNames.addAll(beforeActions);
    actionNames.addAll(afterActions);
    for (Map<String, String> action : actions) {
      actionNames.remove(action.get(NAME));
    }
    if (!actionNames.isEmpty()) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The trigger '" + triggerName + "' does not have actions named: " + actionNames);
    }

    // todo - handle races between competing set-trigger and set-listener invocations
    zkSetListener(container.getZkController().getZkStateReader(), listenerName, op.getValuesExcluding("name"));
    rsp.getValues().add("result", "success");
  }

  private void zkSetListener(ZkStateReader reader, String listenerName, Map<String, Object> listenerProperties) throws KeeperException, InterruptedException {
    while (true) {
      Stat stat = new Stat();
      ZkNodeProps loaded = null;
      byte[] data = reader.getZkClient().getData(SOLR_AUTOSCALING_CONF_PATH, null, stat, true);
      loaded = ZkNodeProps.load(data);
      Map<String, Object> listeners = (Map<String, Object>) loaded.get("listeners");
      if (listeners == null) listeners = new HashMap<>(1);
      if (listenerProperties != null) {
        listeners.put(listenerName, listenerProperties);
      } else {
        listeners.remove(listenerName);
      }
      loaded = loaded.plus("listeners", listeners);
      try {
        reader.getZkClient().setData(SOLR_AUTOSCALING_CONF_PATH, Utils.toJSON(loaded), stat.getVersion(), true);
      } catch (KeeperException.BadVersionException bve) {
        // somebody else has changed the configuration so we must retry
        continue;
      }
      break;
    }
  }

  private void handleSetTrigger(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException {
    String triggerName = op.getStr(NAME);

    if (triggerName == null || triggerName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The trigger name cannot be null or empty");
    }

    String eventTypeStr = op.getStr(EVENT);
    if (eventTypeStr == null || eventTypeStr.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The event type cannot be null or empty in trigger: " + triggerName);
    }
    AutoScaling.EventType eventType = AutoScaling.EventType.valueOf(eventTypeStr.trim().toUpperCase(Locale.ROOT));

    String waitForStr = op.getStr(WAIT_FOR, null);
    if (waitForStr != null) {
      int seconds = 0;
      try {
        seconds = parseHumanTime(waitForStr);
      } catch (IllegalArgumentException e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Invalid 'waitFor' value in trigger: " + triggerName);
      }
      op.getDataMap().put(WAIT_FOR, seconds);
    }

    Integer lowerBound = op.getInt(LOWER_BOUND, null);
    Integer upperBound = op.getInt(UPPER_BOUND, null);

    List<Map<String, String>> actions = (List<Map<String, String>>) op.getVal(ACTIONS);
    if (actions == null) {
      actions = DEFAULT_ACTIONS;
      op.getDataMap().put(ACTIONS, actions);
    }

    // validate that we can load all the actions
    // todo nocommit -- what about MemClassLoader?
    for (Map<String, String> action : actions) {
      if (!action.containsKey(NAME) || !action.containsKey(CLASS)) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No 'name' or 'class' specified for action: " + action);
      }
      String klass = action.get(CLASS);
      try {
        container.getResourceLoader().findClass(klass, TriggerAction.class);
      } catch (Exception e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Action not found: " + klass, e);
      }
    }

    zkSetTrigger(container.getZkController().getZkStateReader(), triggerName, op.getValuesExcluding("name"));
    rsp.getValues().add("result", "success");
  }

  private int parseHumanTime(String timeStr) {
    char c = timeStr.charAt(timeStr.length() - 1);
    long timeValue = Long.parseLong(timeStr.substring(0, timeStr.length() - 1));
    int seconds;
    switch (c) {
      case 'h':
        seconds = (int) TimeUnit.HOURS.toSeconds(timeValue);
        break;
      case 'm':
        seconds = (int) TimeUnit.MINUTES.toSeconds(timeValue);
        break;
      case 's':
        seconds = (int) timeValue;
        break;
      default:
        throw new IllegalArgumentException("Invalid time value");
    }
    return seconds;
  }

  private void handleRemoveTrigger(SolrQueryRequest req, SolrQueryResponse rsp, CommandOperation op) throws KeeperException, InterruptedException {
    String triggerName = op.getStr(NAME);
    boolean removeListeners = op.getBoolean(REMOVE_LISTENERS, false);

    if (triggerName == null || triggerName.trim().length() == 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The trigger name cannot be null or empty");
    }
    Map<String, Object> autoScalingConf = zkReadAutoScalingConf(container.getZkController().getZkStateReader());
    Map<String, Object> triggers = (Map<String, Object>) autoScalingConf.get("triggers");
    if (triggers == null || !triggers.containsKey(triggerName)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No trigger exists with name: " + triggerName);
    }

    Map<String, Map<String, Object>> listeners = (Map<String, Map<String, Object>>) autoScalingConf.get("listeners");
    Set<String> activeListeners = new HashSet<>();
    if (listeners != null) {
      for (Map.Entry<String, Map<String, Object>> entry : listeners.entrySet()) {
        Map<String, Object> listenerProps = entry.getValue();
        if (triggerName.equals(listenerProps.get(TRIGGER)) && !removeListeners) {
          activeListeners.add(entry.getKey());
        }
      }
    }
    if (removeListeners) {
      for (String activeListener : activeListeners) {
        zkSetListener(container.getZkController().getZkStateReader(), activeListener, null);
      }
    } else if (!activeListeners.isEmpty()) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "Cannot remove trigger: " + triggerName + " because it has active listeners: " + activeListeners);
    }

    zkSetTrigger(container.getZkController().getZkStateReader(), triggerName, null);
    rsp.getValues().add("result", "success");
  }

  private void zkSetTrigger(ZkStateReader reader, String triggerName, Map<String, Object> triggerProperties) throws KeeperException, InterruptedException {
    while (true) {
      Stat stat = new Stat();
      ZkNodeProps loaded = null;
      byte[] data = reader.getZkClient().getData(SOLR_AUTOSCALING_CONF_PATH, null, stat, true);
      loaded = ZkNodeProps.load(data);
      Map<String, Object> triggers = (Map<String, Object>) loaded.get("triggers");
      if (triggers == null) triggers = new HashMap<>(1);
      if (triggerProperties != null) {
        triggers.put(triggerName, triggerProperties);
      } else {
        triggers.remove(triggerName);
      }
      loaded = loaded.plus("triggers", triggers);
      try {
        reader.getZkClient().setData(SOLR_AUTOSCALING_CONF_PATH, Utils.toJSON(loaded), stat.getVersion(), true);
      } catch (KeeperException.BadVersionException bve) {
        // somebody else has changed the configuration so we must retry
        continue;
      }
      break;
    }
  }

  private void zkSetPolicies(ZkStateReader reader, String policyBeRemoved, Map<String, Object> newPolicies) throws KeeperException, InterruptedException, IOException {
    while (true) {
      Stat stat = new Stat();
      ZkNodeProps loaded = null;
      byte[] data = reader.getZkClient().getData(SOLR_AUTOSCALING_CONF_PATH, null, stat, true);
      loaded = ZkNodeProps.load(data);
      Map<String, Object> policies = (Map<String, Object>) loaded.get("policies");
      if (policies == null) policies = new HashMap<>(1);
      if (newPolicies != null) {
        policies.putAll(newPolicies);
      } else {
        policies.remove(policyBeRemoved);
      }
      loaded = loaded.plus("policies", policies);
      verifyAutoScalingConf(loaded.getProperties());
      try {
        reader.getZkClient().setData(SOLR_AUTOSCALING_CONF_PATH, Utils.toJSON(loaded), stat.getVersion(), true);
      } catch (KeeperException.BadVersionException bve) {
        // somebody else has changed the configuration so we must retry
        continue;
      }
      break;
    }
  }

  private void zkSetPreferences(ZkStateReader reader, List preferences) throws KeeperException, InterruptedException, IOException {
    while (true) {
      Stat stat = new Stat();
      ZkNodeProps loaded = null;
      byte[] data = reader.getZkClient().getData(SOLR_AUTOSCALING_CONF_PATH, null, stat, true);
      loaded = ZkNodeProps.load(data);
      loaded = loaded.plus("cluster-preferences", preferences);
      verifyAutoScalingConf(loaded.getProperties());
      try {
        reader.getZkClient().setData(SOLR_AUTOSCALING_CONF_PATH, Utils.toJSON(loaded), stat.getVersion(), true);
      } catch (KeeperException.BadVersionException bve) {
        // somebody else has changed the configuration so we must retry
        continue;
      }
      break;
    }
  }

  private void zkSetClusterPolicy(ZkStateReader reader, List clusterPolicy) throws KeeperException, InterruptedException, IOException {
    while (true) {
      Stat stat = new Stat();
      ZkNodeProps loaded = null;
      byte[] data = reader.getZkClient().getData(SOLR_AUTOSCALING_CONF_PATH, null, stat, true);
      loaded = ZkNodeProps.load(data);
      loaded = loaded.plus("cluster-policy", clusterPolicy);
      verifyAutoScalingConf(loaded.getProperties());
      try {
        reader.getZkClient().setData(SOLR_AUTOSCALING_CONF_PATH, Utils.toJSON(loaded), stat.getVersion(), true);
      } catch (KeeperException.BadVersionException bve) {
        // somebody else has changed the configuration so we must retry
        continue;
      }
      break;
    }
  }

  private void verifyAutoScalingConf(Map<String, Object> autoScalingConf) throws IOException {
    try (CloudSolrClient build = new CloudSolrClient.Builder()
        .withHttpClient(container.getUpdateShardHandler().getHttpClient())
        .withZkHost(container.getZkController().getZkServerAddress()).build()) {
      Policy policy = new Policy(autoScalingConf);
      Policy.Session session = policy.createSession(new SolrClientDataProvider(build));
      log.debug("Verified autoscaling configuration");
    }
  }

  private Map<String, Object> zkReadAutoScalingConf(ZkStateReader reader) throws KeeperException, InterruptedException {
    byte[] data = reader.getZkClient().getData(SOLR_AUTOSCALING_CONF_PATH, null, null, true);
    ZkNodeProps loaded = ZkNodeProps.load(data);
    return loaded.getProperties();
  }

  @Override
  public String getDescription() {
    return "A handler for autoscaling configuration";
  }

  @Override
  public Name getPermissionName(AuthorizationContext request) {
    switch (request.getHttpMethod()) {
      case "GET":
        return Name.AUTOSCALING_READ_PERM;
      case "POST":
        return Name.AUTOSCALING_WRITE_PERM;
      default:
        return null;
    }
  }

  @Override
  public Collection<Api> getApis() {
    return ApiBag.wrapRequestHandlers(this, "autoscaling.Commands");
  }

  @Override
  public Boolean registerV2() {
    return Boolean.TRUE;
  }

  @Override
  public SolrRequestHandler getSubHandler(String path) {
    if (path.equals("/diagnostics")) return this;
    return null;
  }
}
