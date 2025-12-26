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

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
public class TodoItemResourceTest {

    @Test
    public void testList() {
        given()
          .when().get("/")
          .then()
             .statusCode(200)
             .body(notNullValue());
    }

    @Test
    public void testSave() {
        given()
          .contentType("application/json")
          .body("{\"title\":\"Test Todo\",\"completed\":false}")
          .when().post("/save")
          .then()
             .statusCode(201)
             .body("title", notNullValue())
             .body("id", notNullValue());
    }
}
