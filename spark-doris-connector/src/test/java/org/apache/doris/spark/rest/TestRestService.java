// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.spark.rest;

import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_FENODES;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLET_SIZE;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLET_SIZE_DEFAULT;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLET_SIZE_MIN;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLE_IDENTIFIER;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_REQUEST_TABLET_BATCH;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_REQUEST_TABLET_BATCH_DEFAULT;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_REQUEST_TABLET_CURSOR_OFFSET;

import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_REQUEST_TABLET_BATCH_FLAG;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.doris.spark.cfg.PropertiesSettings;
import org.apache.doris.spark.cfg.Settings;
import org.apache.doris.spark.exception.DorisException;
import org.apache.doris.spark.exception.IllegalArgumentException;
import org.apache.doris.spark.rest.models.Field;
import org.apache.doris.spark.rest.models.QueryPlan;
import org.apache.doris.spark.rest.models.Schema;
import org.apache.doris.spark.rest.models.Tablet;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestRestService {
    private static Logger logger = LoggerFactory.getLogger(TestRestService.class);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testParseIdentifier() throws Exception {
        String validIdentifier = "a.b";
        String[] names = RestService.parseIdentifier(validIdentifier, logger);
        Assert.assertEquals(2, names.length);
        Assert.assertEquals("a", names[0]);
        Assert.assertEquals("b", names[1]);

        String invalidIdentifier1 = "a";
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("argument 'table.identifier' is illegal, value is '" + invalidIdentifier1 + "'.");
        RestService.parseIdentifier(invalidIdentifier1, logger);

        String invalidIdentifier3 = "a.b.c";
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("argument 'table.identifier' is illegal, value is '" + invalidIdentifier3 + "'.");
        RestService.parseIdentifier(invalidIdentifier3, logger);

        String emptyIdentifier = "";
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("argument 'table.identifier' is illegal, value is '" + emptyIdentifier + "'.");
        RestService.parseIdentifier(emptyIdentifier, logger);

        String nullIdentifier = null;
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("argument 'table.identifier' is illegal, value is '" + nullIdentifier + "'.");
        RestService.parseIdentifier(nullIdentifier, logger);
    }

    @Test
    public void testChoiceFe() throws Exception {
        String validFes = "1,2 , 3";
        String fe = RestService.randomEndpoint(validFes, logger);
        List<String> feNodes = new ArrayList<>(3);
        feNodes.add("1");
        feNodes.add("2");
        feNodes.add("3");
        Assert.assertTrue(feNodes.contains(fe));

        String emptyFes = "";
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("argument 'fenodes' is illegal, value is '" + emptyFes + "'.");
        RestService.randomEndpoint(emptyFes, logger);

        String nullFes = null;
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("argument 'fenodes' is illegal, value is '" + nullFes + "'.");
        RestService.randomEndpoint(nullFes, logger);
    }

    @Test
    public void testGetUriStr() throws Exception {
        Settings settings = new PropertiesSettings();
        settings.setProperty(DORIS_TABLE_IDENTIFIER, "a.b");
        settings.setProperty(DORIS_FENODES, "fe");

        String expected = "http://fe/api/a/b/";
        Assert.assertEquals(expected, RestService.getUriStr(settings, logger));
    }

    @Test
    public void testFeResponseToSchema() throws Exception {
        String res = "{\"properties\":[{\"type\":\"TINYINT\",\"name\":\"k1\",\"comment\":\"\"},{\"name\":\"k5\","
                + "\"scale\":\"0\",\"comment\":\"\",\"type\":\"DECIMALV2\",\"precision\":\"9\"}],\"status\":200}";
        Schema expected = new Schema();
        expected.setStatus(200);
        Field k1 = new Field("k1", "TINYINT", "", 0, 0);
        Field k5 = new Field("k5", "DECIMALV2", "", 9, 0);
        expected.put(k1);
        expected.put(k5);
        Assert.assertEquals(expected, RestService.parseSchema(res, logger));

        String notJsonRes = "not json";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Doris FE's response is not a json. res:"));
        RestService.parseSchema(notJsonRes, logger);

        String notSchemaRes = "{\"property\":[{\"type\":\"TINYINT\",\"name\":\"k1\",\"comment\":\"\"},"
                + "{\"name\":\"k5\",\"scale\":\"0\",\"comment\":\"\",\"type\":\"DECIMALV2\",\"precision\":\"9\"}],"
                + "\"status\":200}";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Doris FE's response cannot map to schema. res: "));
        RestService.parseSchema(notSchemaRes, logger);

        String notOkRes = "{\"properties\":[{\"type\":\"TINYINT\",\"name\":\"k1\",\"comment\":\"\"},{\"name\":\"k5\","
                + "\"scale\":\"0\",\"comment\":\"\",\"type\":\"DECIMALV2\",\"precision\":\"9\"}],\"status\":20}";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Doris FE's response is not OK, status is "));
        RestService.parseSchema(notOkRes, logger);
    }

    @Test
    public void testFeResponseToQueryPlan() throws Exception {
        String res = "{\"partitions\":{"
                + "\"11017\":{\"routings\":[\"be1\",\"be2\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1},"
                + "\"11019\":{\"routings\":[\"be3\",\"be4\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1}},"
                + "\"opaqued_query_plan\":\"query_plan\",\"status\":200}";

        List<String> routings11017 = new ArrayList<>(2);
        routings11017.add("be1");
        routings11017.add("be2");

        Tablet tablet11017 = new Tablet();
        tablet11017.setSchemaHash(1);
        tablet11017.setVersionHash(1);
        tablet11017.setVersion(3);
        tablet11017.setRoutings(routings11017);

        List<String> routings11019 = new ArrayList<>(2);
        routings11019.add("be3");
        routings11019.add("be4");

        Tablet tablet11019 = new Tablet();
        tablet11019.setSchemaHash(1);
        tablet11019.setVersionHash(1);
        tablet11019.setVersion(3);
        tablet11019.setRoutings(routings11019);

        Map<String, Tablet> partitions = new LinkedHashMap<>();
        partitions.put("11017", tablet11017);
        partitions.put("11019", tablet11019);

        QueryPlan expected = new QueryPlan();
        expected.setPartitions(partitions);
        expected.setStatus(200);
        expected.setOpaqued_query_plan("query_plan");

        QueryPlan actual = RestService.getQueryPlan(res, logger);
        Assert.assertEquals(expected, actual);

        String notJsonRes = "not json";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Doris FE's response is not a json. res:"));
        RestService.parseSchema(notJsonRes, logger);

        String notQueryPlanRes = "{\"hello\": \"world\"}";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Doris FE's response cannot map to schema. res: "));
        RestService.parseSchema(notQueryPlanRes, logger);

        String notOkRes = "{\"partitions\":{\"11017\":{\"routings\":[\"be1\",\"be2\"],\"version\":3,"
                + "\"versionHash\":1,\"schemaHash\":1}},\"opaqued_query_plan\":\"queryPlan\",\"status\":20}";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Doris FE's response is not OK, status is "));
        RestService.parseSchema(notOkRes, logger);
    }

    @Test
    public void testSelectTabletBe() throws Exception {
        String res = "{\"partitions\":{"
                + "\"11017\":{\"routings\":[\"be1\",\"be2\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1},"
                + "\"11019\":{\"routings\":[\"be3\",\"be4\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1},"
                + "\"11021\":{\"routings\":[\"be3\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1},"
                + "\"11023\":{\"routings\":[\"be2\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1},"
                + "\"11122\":{\"routings\":[\"be1\", \"be3\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1},"
                + "\"13422\":{\"routings\":[\"be3\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1}},"
                + "\"opaqued_query_plan\":\"query_plan\",\"status\":200}";
        /*[11017,11019,11021,11023,11122,13422]
	      offset = 0
          [11017,11019,11021]
           offset = 3 
          [11023,11122,13422]
           offset = 6
	*/
	Settings settings = new PropertiesSettings();
	settings.setProperty(DORIS_REQUEST_TABLET_BATCH,"3");
        settings.setProperty(DORIS_REQUEST_TABLET_CURSOR_OFFSET, "0");
	settings.setProperty(DORIS_REQUEST_TABLET_BATCH_FLAG, "true");
        QueryPlan queryPlan = RestService.getQueryPlan(res, logger);

        List<Long> be1Tablet = new ArrayList<>();
        List<Long> be2Tablet = new ArrayList<>();
	List<Long> be3Tablet = new ArrayList<>();
	List<Long> be4Tablet = new ArrayList<>();
        be1Tablet.add(11017L);
	be3Tablet.add(11019L);
        be3Tablet.add(11021L);
        Map<String, List<Long>> expected = new HashMap<>();
        expected.put("be1", be1Tablet);
        expected.put("be3", be3Tablet);
	    //expected : {"be1": [11017L],"be3" : [11021L,11019L]};
	    //be3=[11019, 11021, 13422], be2=[11023], be1=[11017, 11122]
        Assert.assertEquals(expected, RestService.selectBeForTablet(settings,queryPlan, logger));
	    System.out.println("Test------------------------1");
        expected.clear();
        be1Tablet.clear();
	    be3Tablet.clear();
	    be2Tablet.clear();
	    settings.setProperty(DORIS_REQUEST_TABLET_CURSOR_OFFSET, "1");
	    queryPlan = RestService.getQueryPlan(res, logger);
        be2Tablet.add(11023L);
	    be1Tablet.add(11122L);
        be3Tablet.add(13422L);
        expected.put("be2",be2Tablet);
	    expected.put("be3",be3Tablet);
	    expected.put("be1",be1Tablet);
        //expected : {"be3": [113422L],"be2": [11023L] "be1": [11122L]}
        Assert.assertEquals(expected, RestService.selectBeForTablet(settings, queryPlan, logger));
	    System.out.println("Test------------------------2");
	    expected.clear();
	    be3Tablet.clear();
	    be1Tablet.clear();
        be2Tablet.clear();
	    /*queryPlan = RestService.getQueryPlan(res, logger);
	    settings.setProperty(DORIS_REQUEST_TABLET_CURSOR_OFFSET, "2");
	    be3Tablet.add(11021L);
	    be3Tablet.add(13422L);
	    expected.put("be3", be3Tablet);
	    //expected : {"be3": [11021L, 13422L]}
	    Assert.assertEquals(expected, RestService.selectBeForTablet(settings, queryPlan, logger));
	    System.out.println("Test------------------------3");*/
	    settings.setProperty(DORIS_REQUEST_TABLET_CURSOR_OFFSET, "0");
        String noBeRes = "{\"partitions\":{"
                + "\"11021\":{\"routings\":[],\"version\":3,\"versionHash\":1,\"schemaHash\":1}},"
                + "\"opaqued_query_plan\":\"query_plan\",\"status\":200}";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Cannot choice Doris BE for tablet"));
        RestService.selectBeForTablet(settings,RestService.getQueryPlan(noBeRes, logger), logger);

        String notNumberRes = "{\"partitions\":{"
                + "\"11021xxx\":{\"routings\":[\"be1\"],\"version\":3,\"versionHash\":1,\"schemaHash\":1}},"
                + "\"opaqued_query_plan\":\"query_plan\",\"status\":200}";
        thrown.expect(DorisException.class);
        thrown.expectMessage(startsWith("Parse tablet id "));
        RestService.selectBeForTablet(settings, RestService.getQueryPlan(noBeRes, logger), logger);
    }

    @Test
    public void testGetTabletSize() {
        Settings settings = new PropertiesSettings();
        Assert.assertEquals(DORIS_TABLET_SIZE_DEFAULT, RestService.tabletCountLimitForOnePartition(settings, logger));

        settings.setProperty(DORIS_TABLET_SIZE, "xx");
        Assert.assertEquals(DORIS_TABLET_SIZE_DEFAULT, RestService.tabletCountLimitForOnePartition(settings, logger));

        settings.setProperty(DORIS_TABLET_SIZE, "10");
        Assert.assertEquals(10, RestService.tabletCountLimitForOnePartition(settings, logger));

        settings.setProperty(DORIS_TABLET_SIZE, "1");
        Assert.assertEquals(DORIS_TABLET_SIZE_MIN, RestService.tabletCountLimitForOnePartition(settings, logger));
    }

    @Test
    public void testTabletsMapToPartition() throws Exception {
        List<Long> tablets1 = new ArrayList<>();
        tablets1.add(1L);
        tablets1.add(2L);
        List<Long> tablets2 = new ArrayList<>();
        tablets2.add(3L);
        tablets2.add(4L);
        Map<String, List<Long>> beToTablets = new HashMap<>();
        beToTablets.put("be1", tablets1);
        beToTablets.put("be2", tablets2);

        Settings settings = new PropertiesSettings();
        String opaquedQueryPlan = "query_plan";
        String cluster = "c";
        String database = "d";
        String table = "t";

        Set<Long> be1Tablet = new HashSet<>();
        be1Tablet.add(1L);
        be1Tablet.add(2L);
        PartitionDefinition pd1 = new PartitionDefinition(
                database, table, settings, "be1", be1Tablet, opaquedQueryPlan);

        Set<Long> be2Tablet = new HashSet<>();
        be2Tablet.add(3L);
        be2Tablet.add(4L);
        PartitionDefinition pd2 = new PartitionDefinition(
                database, table, settings, "be2", be2Tablet, opaquedQueryPlan);

        List<PartitionDefinition> expected = new ArrayList<>();
        expected.add(pd1);
        expected.add(pd2);
        Collections.sort(expected);

        List<PartitionDefinition> actual = RestService.tabletsMapToPartition(
                settings, beToTablets, opaquedQueryPlan, database, table, logger);
        Collections.sort(actual);

        Assert.assertEquals(expected, actual);
    }
}
