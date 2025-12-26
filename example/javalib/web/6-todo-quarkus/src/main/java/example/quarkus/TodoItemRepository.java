/*
 * Copyright 2025 Example Organization
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example.quarkus;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TodoItemRepository implements PanacheRepository<TodoItem> {

  public long countByCompleted(boolean completed) {
    return count("completed", completed);
  }

  public List<TodoItem> findByCompleted(boolean completed) {
    return list("completed", completed);
  }

  public void updateCompleted(boolean completed) {
    update("completed = ?1", completed);
  }

  public void updateCompletedById(Long id, boolean completed) {
    update("completed = ?1 where id = ?2", completed, id);
  }
}
