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
package org.firej.cache;

public final class NullCache implements RegexCache {
    @Override
    public RegexTemplate get(String regex) {
        return null;
    }

    @Override
    public void put(String regex, RegexTemplate template) {
        // discarded
    }

    @Override
    public void clear() {
        // no-op
    }

    @Override
    public void remove(String regex) {
        // nothing is kept
    }

    @Override
    public String name() {
        return "DISABLED";
    }
}
