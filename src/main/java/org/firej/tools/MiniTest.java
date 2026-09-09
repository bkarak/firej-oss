/*
 * Copyright 2008-2026 Vassilios Karakoidas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.firej.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.firej.Regex;
import org.firej.RegexFactory;

/**
 * Prints the original MiniTest cases. The JUnit suite is the real contract.
 */
public final class MiniTest {
    private MiniTest() {
    }

    public static Map<String, String> cases() {
        Map<String, String> redat = new LinkedHashMap<>();
        redat.put("[0-9]*", "555555555");
        redat.put("(([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])\\.){3}([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])",
                "193.92.177.2");
        redat.put("[0-9]{3}-[0-9]{2}-[0-9]{4}", "333-22-4444");
        redat.put("[A-Za-z0-9]{8}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{12}",
                "BFDB4D31-3E35-4DAB-AFCA-5E6E5C8F61EA");
        redat.put("[a-zA-Z0-9]+([a-zA-Z0-9\\-\\.]+)?\\.(com|org|net|mil|edu|COM|ORG|NET|MIL|EDU)",
                "my.domain.com");
        return redat;
    }

    public static void run() {
        int c = 0;
        for (Map.Entry<String, String> e : cases().entrySet()) {
            c++;
            Regex r = RegexFactory.createRegex(e.getKey());
            boolean ok = r.matches(e.getValue());
            System.out.printf("Test [%d]: Compiled(true), Matching(%s)%n", c, ok);
        }
    }
}
