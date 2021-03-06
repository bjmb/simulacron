/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.simulacron.http.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.oss.simulacron.common.cluster.ClusterSpec;
import com.datastax.oss.simulacron.common.cluster.ObjectMapperHolder;
import com.datastax.oss.simulacron.common.cluster.RequestPrime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class HttpPrimeQueryIntegrationTest {

  @Rule public AdminServer server = new AdminServer(ClusterSpec.builder().withNodes(1).build());

  ObjectMapper om = ObjectMapperHolder.getMapper();

  @Test
  public void testQueryPrimeSimple() throws Exception {
    RequestPrime prime = HttpTestUtil.createSimplePrimedQuery("Select * FROM TABLE2");
    HttpTestResponse response = server.prime(prime);
    assertNotNull(response);
    RequestPrime responseQuery = om.readValue(response.body, RequestPrime.class);
    assertThat(responseQuery).isEqualTo(prime);

    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set = HttpTestUtil.makeNativeQuery("Select * FROM TABLE2", contactPoint);
    List<Row> results = set.all();
    assertThat(1).isEqualTo(results.size());
    Row row1 = results.get(0);
    String column1 = row1.getString("column1");
    assertThat(column1).isEqualTo("column1");
    Long column2 = row1.getLong("column2");
    assertThat(column2).isEqualTo(new Long(2));
  }

  @Test
  public void testQueryPrimeNamedParamSimple() throws Exception {
    HashMap<String, String> paramTypes = new HashMap<>();
    paramTypes.put("id", "bigint");
    paramTypes.put("id2", "bigint");
    HashMap<String, Object> params = new HashMap<>();
    params.put("id", new Long(1));
    params.put("id2", new Long(2));
    RequestPrime prime =
        HttpTestUtil.createSimpleParameterizedQuery(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", params, paramTypes);

    HttpTestResponse response = server.prime(prime);
    assertNotNull(response);
    RequestPrime responseQuery = om.readValue(response.body, RequestPrime.class);
    assertThat(responseQuery).isEqualTo(prime);
    Map<String, Object> values =
        ImmutableMap.<String, Object>of("id", new Long(1), "id2", new Long(2));
    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set =
        HttpTestUtil.makeNativeQueryWithNameParams(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", contactPoint, values);
    List<Row> results = set.all();
    assertThat(1).isEqualTo(results.size());
    Row row1 = results.get(0);
    String column1 = row1.getString("column1");
    assertThat(column1).isEqualTo("column1");
    Long column2 = row1.getLong("column2");
    assertThat(column2).isEqualTo(new Long(2));

    // Try with the wrong values
    values = ImmutableMap.<String, Object>of("id", new Long(2), "id2", new Long(2));
    set =
        HttpTestUtil.makeNativeQueryWithNameParams(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", contactPoint, values);
    assertThat(set.all().size()).isEqualTo(0);
    // Try with the wrong number of values
    values = ImmutableMap.<String, Object>of("id", new Long(2));
    set =
        HttpTestUtil.makeNativeQueryWithNameParams(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", contactPoint, values);
    assertThat(set.all().size()).isEqualTo(0);
    // No values
    values = ImmutableMap.<String, Object>of();
    set =
        HttpTestUtil.makeNativeQueryWithNameParams(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", contactPoint, values);
    assertThat(set.all().size()).isEqualTo(0);
  }

  @Test
  public void testQueryPositionalParamSimple() throws Exception {
    HashMap<String, String> paramTypes = new HashMap<>();
    paramTypes.put("c1", "ascii");
    HashMap<String, Object> params = new HashMap<>();
    params.put("c1", "c1");
    RequestPrime prime =
        HttpTestUtil.createSimpleParameterizedQuery(
            "SELECT table FROM foo WHERE c1=?", params, paramTypes);
    HttpTestResponse response = server.prime(prime);
    assertNotNull(response);
    RequestPrime responseQuery = om.readValue(response.body, RequestPrime.class);
    assertThat(responseQuery).isEqualTo(prime);
    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set =
        HttpTestUtil.makeNativeQueryWithPositionalParams(
            "SELECT table FROM foo WHERE c1=?", contactPoint, "c1");
    List<Row> results = set.all();
    assertThat(1).isEqualTo(results.size());
    Row row1 = results.get(0);
    String column1 = row1.getString("column1");
    assertThat(column1).isEqualTo("column1");
    Long column2 = row1.getLong("column2");
    // Extra Param
    set =
        HttpTestUtil.makeNativeQueryWithPositionalParams(
            "SELECT table FROM foo WHERE c1=?", contactPoint, "c1", "extraParam");
    assertThat(set.all().size()).isEqualTo(0);
    // Wrong Param
    set =
        HttpTestUtil.makeNativeQueryWithPositionalParams(
            "SELECT table FROM foo WHERE ci1=?", contactPoint, "d1");
    assertThat(set.all().size()).isEqualTo(0);
  }

  @Test
  public void testBoundStatementPositional() throws Exception {
    HashMap<String, String> paramTypes = new HashMap<>();
    paramTypes.put("c1", "ascii");
    HashMap<String, Object> params = new HashMap<>();
    params.put("c1", "c1");
    RequestPrime prime =
        HttpTestUtil.createSimpleParameterizedQuery(
            "SELECT table FROM foo WHERE c1=?", params, paramTypes);
    HttpTestResponse response = server.prime(prime);
    assertNotNull(response);
    RequestPrime responseQuery = om.readValue(response.body, RequestPrime.class);
    assertThat(responseQuery).isEqualTo(prime);
    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set =
        HttpTestUtil.makeNativeBoundQueryWithPositionalParam(
            "SELECT table FROM foo WHERE c1=?", contactPoint, "c1");
    List<Row> results = set.all();
    assertThat(1).isEqualTo(results.size());
    Row row1 = results.get(0);
    String column1 = row1.getString("column1");
    assertThat(column1).isEqualTo("column1");
    Long column2 = row1.getLong("column2");
  }

  @Test
  public void testBoundStatementPositionalAutoPrime() {
    HashMap<String, String> paramTypes = new HashMap<>();
    paramTypes.put("c1", "ascii");
    HashMap<String, Object> params = new HashMap<>();
    params.put("c1", "c1");
    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set =
        HttpTestUtil.makeNativeBoundQueryWithPositionalParam(
            "SELECT table FROM foo2 WHERE c1=?", contactPoint, "d1");
    assertThat(set.all().size()).isEqualTo(0);
  }

  @Test
  public void testBoundStatementNamed() throws Exception {
    HashMap<String, String> paramTypes = new HashMap<>();
    paramTypes.put("id", "bigint");
    paramTypes.put("id2", "bigint");
    HashMap<String, Object> params = new HashMap<>();
    params.put("id", new Long(1));
    params.put("id2", new Long(2));
    RequestPrime prime =
        HttpTestUtil.createSimpleParameterizedQuery(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", params, paramTypes);

    HttpTestResponse response = server.prime(prime);
    assertNotNull(response);
    RequestPrime responseQuery = (RequestPrime) om.readValue(response.body, RequestPrime.class);
    assertThat(responseQuery).isEqualTo(prime);
    Map<String, Long> values = ImmutableMap.<String, Long>of("id", new Long(1), "id2", new Long(2));
    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set =
        HttpTestUtil.makeNativeBoundQueryWithNameParams(
            "SELECT * FROM users WHERE id = :id and id2 = :id2", contactPoint, values);
    List<Row> results = set.all();
    assertThat(1).isEqualTo(results.size());
    Row row1 = results.get(0);
    String column1 = row1.getString("column1");
    assertThat(column1).isEqualTo("column1");
    Long column2 = row1.getLong("column2");
    assertThat(column2).isEqualTo(new Long(2));
    Map<String, String> values2 = ImmutableMap.<String, String>of("id", "1", "id2", "2");
  }

  @Test
  public void testBoundStatementNamedAutoPrime() {
    HashMap<String, String> paramTypes = new HashMap<>();
    paramTypes.put("id", "bigint");
    paramTypes.put("id2", "bigint");
    HashMap<String, Object> params = new HashMap<>();
    params.put("id", new Long(1));
    params.put("id2", new Long(2));
    Map<String, String> values2 = ImmutableMap.<String, String>of("id", "1", "id2", "2");
    String contactPoint = HttpTestUtil.getContactPointString(server.getCluster(), 0);
    ResultSet set =
        HttpTestUtil.makeNativeBoundQueryWithNameParamsStrings(
            "SELECT * FROM users2 WHERE id = :id and id2 = :id2", contactPoint, values2);
    assertThat(set.all().size()).isEqualTo(0);
  }
}
