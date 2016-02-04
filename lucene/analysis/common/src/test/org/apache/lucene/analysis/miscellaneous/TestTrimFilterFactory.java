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
package org.apache.lucene.analysis.miscellaneous;


import java.io.Reader;
import java.io.StringReader;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.util.BaseTokenStreamFactoryTestCase;
import org.apache.lucene.util.Version;

/**
 * Simple tests to ensure this factory is working
 */
public class TestTrimFilterFactory extends BaseTokenStreamFactoryTestCase {
  public void testTrimming() throws Exception {
    Reader reader = new StringReader("trim me    ");
    TokenStream stream = keywordMockTokenizer(reader);
    stream = tokenFilterFactory("Trim").create(stream);
    assertTokenStreamContents(stream, new String[] { "trim me" });
  }
  
  /** Test that bogus arguments result in exception */
  public void testBogusArguments() throws Exception {
    try {
      tokenFilterFactory("Trim", "bogusArg", "bogusValue");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Unknown parameters"));
    }
  }
  
  public void test43Backcompat() throws Exception {
    Reader reader = new StringReader("  foo ");
    TokenStream stream = keywordMockTokenizer(reader);
    stream = tokenFilterFactory("Trim", Version.LUCENE_4_3_1, "updateOffsets", "true").create(stream);
    assertTrue(stream instanceof Lucene43TrimFilter);
    assertTokenStreamContents(stream, new String[] {"foo"}, new int[] {2}, new int[] {5});
    
    try {
      tokenFilterFactory("Trim", Version.LUCENE_4_4_0, "updateOffsets", "true");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("updateOffsets=true is not supported"));
    }
    tokenFilterFactory("Trim", Version.LUCENE_4_4_0, "updateOffsets", "false");

    try {
      tokenFilterFactory("Trim", "updateOffsets", "false");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("not a valid option"));
    }
  }
}
