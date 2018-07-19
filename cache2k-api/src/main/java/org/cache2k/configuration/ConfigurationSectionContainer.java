package org.cache2k.configuration;

/*
 * #%L
 * cache2k API
 * %%
 * Copyright (C) 2000 - 2018 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Container for configuration objects. The container preserves the order of the sections
 * and checks that one type is only added once.
 *
 * @author Jens Wilke
 * @see ConfigurationWithSections
 */
public class ConfigurationSectionContainer implements Iterable<ConfigurationSection>, Serializable {

  private List<ConfigurationSection> sections = new ArrayList<ConfigurationSection>();

  /**
   * Add a new configuration section to the container.
   *
   * @throws IllegalArgumentException if same type is already present and a singleton
   */
  public void add(ConfigurationSection section) {
    if (section instanceof SingletonConfigurationSection) {
      if (getSection(section.getClass()) !=  null) {
        throw new IllegalArgumentException("Section of same type already inserted: " + section.getClass().getName());
      }
    }
    sections.add(section);
  }

  /**
   * Retrieve a single section from the container.
   */
  public <T extends ConfigurationSection> T getSection(Class<T> sectionType) {
    for (ConfigurationSection s : sections) {
      if (sectionType.equals(s.getClass())) {
        return (T) s;
      }
    }
    return null;
  }

  @Override
  public Iterator<ConfigurationSection> iterator() {
    return sections.iterator();
  }

}
