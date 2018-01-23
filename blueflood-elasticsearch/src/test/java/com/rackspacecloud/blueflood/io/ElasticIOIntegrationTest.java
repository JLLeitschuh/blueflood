/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.io;

import com.github.tlrx.elasticsearch.test.EsSetup;

import com.rackspacecloud.blueflood.service.ElasticIOConfig;
import com.rackspacecloud.blueflood.types.IMetric;
import com.rackspacecloud.blueflood.types.Locator;
import com.rackspacecloud.blueflood.types.Metric;
import com.rackspacecloud.blueflood.types.Token;
import com.rackspacecloud.blueflood.utils.TimeValue;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

//import org.elasticsearch.action.bulk.BulkRequestBuilder;
//import org.elasticsearch.action.index.IndexRequestBuilder;
//import org.elasticsearch.client.Client;

import org.apache.commons.lang.StringUtils;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

//@RunWith( JUnitParamsRunner.class )
public class ElasticIOIntegrationTest extends BaseElasticTest {

    protected static ElasticIO elasticIO;

    @BeforeClass
    public static void setup() throws Exception {
        esSetup = new EsSetup();
        esSetup.execute(EsSetup.deleteAll());

        esSetup.execute(EsSetup.createIndex(ElasticIO.ELASTICSEARCH_INDEX_NAME_WRITE)
                .withSettings(EsSetup.fromClassPath("index_settings.json"))
                .withMapping(ElasticIO.ELASTICSEARCH_DOCUMENT_TYPE, EsSetup.fromClassPath("metrics_mapping.json")));

        elasticIO = new ElasticIO();
        elasticIO.insertDiscovery(createTestMetrics(TENANT_A));
        elasticIO.insertDiscovery(createTestMetrics(TENANT_B));
        elasticIO.insertDiscovery(createTestMetricsFromInterface(TENANT_C));

        Thread.sleep(3*1000);
    }



    @AfterClass
    public static void tearDown() {
        esSetup.terminate();
    }

    @Override
    protected void insertDiscovery(List<IMetric> metrics) throws IOException {
        elasticIO.insertDiscovery(metrics);

        Stream<Locator> locators = metrics.stream().map(IMetric::getLocator);
//        elasticTokensIO.insertDiscovery(Token.getUniqueTokens(locators)
//                                             .collect(toList()));
    }

    @Test(expected=IllegalArgumentException.class)
    public void testCreateSingleRequest_WithNullMetricName() throws IOException {
        Discovery discovery = new Discovery(TENANT_A, null);
        //elasticIO.createSingleRequest(discovery);
    }

//    @Test
//    public void testCreateSingleRequest() throws IOException {
//        final String METRIC_NAME = "a.b.c.m1";
//        Discovery discovery = new Discovery(TENANT_A, METRIC_NAME);
////        IndexRequestBuilder builder = elasticIO.createSingleRequest(discovery);
////        Assert.assertNotNull(builder);
////        assertEquals(TENANT_A + ":" + METRIC_NAME, builder.request().id());
//        final String expectedIndex =
//                "index {" +
//                        "[" + ElasticIO.ELASTICSEARCH_INDEX_NAME_WRITE + "]" +
//                        "[" + ElasticIO.ELASTICSEARCH_DOCUMENT_TYPE + "]" +
//                        "["+ TENANT_A + ":" + METRIC_NAME + "], " +
//                        "source[{" +
//                        "\"tenantId\":\"" + TENANT_A + "\"," +
//                        "\"metric_name\":\"" + METRIC_NAME + "\"" +
//                        "}]}";
////        assertEquals(expectedIndex, builder.request().toString());
////        assertEquals(builder.request().routing(), TENANT_A);
//    }

    @Test
    public void testNoCrossTenantResults() throws Exception {
        List<SearchResult> results = elasticIO.search(TENANT_A, "*");
        assertEquals(NUM_DOCS, results.size());
        for (SearchResult result : results) {
            Assert.assertNotNull(result.getTenantId());
            Assert.assertNotSame(TENANT_B, result.getTenantId());
        }
    }

    @Test
    public void testWildCard() throws Exception {
        testWildcard(TENANT_A, UNIT);
    }

    @Test
    public void testWildcardForPreaggregatedMetric() throws Exception {
        testWildcard(TENANT_C, null);
    }

    @Test
    public void testBatchQueryWithNoWildCards() throws Exception {
        String tenantId = TENANT_A;
        String query1 = "one.two.three00.fourA.five1";
        String query2 = "one.two.three01.fourA.five2";
        List<SearchResult> results;
        ArrayList<String> queries = new ArrayList<String>();
        queries.add(query1);
        queries.add(query2);
        results = elasticIO.search(tenantId, queries);
        assertEquals(results.size(), 2); //we searched for 2 unique metrics
        results.contains(new SearchResult(TENANT_A, query1, UNIT));
        results.contains(new SearchResult(TENANT_A, query2, UNIT));
    }

    @Test
    public void testBatchQueryWithWildCards() throws Exception {
        String tenantId = TENANT_A;
        String query1 = "one.two.three00.fourA.*";
        String query2 = "one.two.*.fourA.five2";
        List<SearchResult> results;
        ArrayList<String> queries = new ArrayList<String>();
        queries.add(query1);
        queries.add(query2);
        results = elasticIO.search(tenantId, queries);
        // query1 will return 3 results, query2 will return 30 results, but we get back 32 because of intersection
        assertEquals(results.size(), 32);
    }

    @Test
    public void testBatchQueryWithWildCards2() throws Exception {
        String tenantId = TENANT_A;
        String query1 = "*.two.three00.fourA.five1";
        String query2 = "*.two.three01.fourA.five2";
        List<SearchResult> results;
        ArrayList<String> queries = new ArrayList<String>();
        queries.add(query1);
        queries.add(query2);
        results = elasticIO.search(tenantId, queries);
        assertEquals(results.size(), 2);
    }

    public void testWildcard(String tenantId, String unit) throws Exception {
        SearchResult entry;
        List<SearchResult> results;
        results = elasticIO.search(tenantId, "one.two.*");
        List<Locator> locators = locatorMap.get(tenantId);
        assertEquals(locators.size(), results.size());
        for (Locator locator : locators) {
            entry =  new SearchResult(tenantId, locator.getMetricName(), unit);

            boolean isFound = false;
            for(SearchResult item : results){
                if((StringUtils.isNotEmpty(item.getMetricName()) && item.getMetricName().equalsIgnoreCase(entry.getMetricName())) &&
                        (StringUtils.isNotEmpty(item.getTenantId()) && item.getTenantId().equalsIgnoreCase(entry.getTenantId()))){
                    isFound = true;
                    break;
                }
            }
            Assert.assertTrue(isFound);
        }

//        //TODO: "*.fourA.*" is failing at this time.
//        results = elasticIO.search(tenantId, "*.fourA.*");
//        assertEquals(NUM_PARENT_ELEMENTS * NUM_GRANDCHILD_ELEMENTS, results.size());
//        for (int x = 0; x < NUM_PARENT_ELEMENTS; x++) {
//            for (int z = 0; z < NUM_GRANDCHILD_ELEMENTS; z++) {
//                entry = createExpectedResult(tenantId, x, "A", z, unit);
//                Assert.assertTrue(results.contains(entry));
//            }
//        }

        results = elasticIO.search(tenantId, "*.three1*.four*.five2");
        assertEquals(10 * CHILD_ELEMENTS.size(), results.size());
        for (int x = 10; x < 20; x++) {
            for (String y : CHILD_ELEMENTS) {
                entry = createExpectedResult(tenantId, x, y, 2, unit);
                Assert.assertTrue(results.contains(entry));
            }
        }
    }

    @Test
    public void testGlobMatching() throws Exception {
        List<SearchResult> results = elasticIO.search(TENANT_A, "one.two.{three00,three01}.fourA.five0");
        assertEquals(results.size(), 2);
        results.contains(new SearchResult(TENANT_A, "one.two.three00.fourA.five0", UNIT));
        results.contains(new SearchResult(TENANT_A, "one.two.three01.fourA.five0", UNIT));
    }

    @Test
    public void testGlobMatching2() throws Exception {
        List<SearchResult> results = elasticIO.search(TENANT_A, "one.two.three0?.fourA.five0");
        List<SearchResult> results2 = elasticIO.search(TENANT_A, "one.two.three0[0-9].fourA.five0");
        assertEquals(10, results.size());
        for (SearchResult result : results) {
            Assert.assertTrue(result.getMetricName().startsWith("one.two.three"));
            assertEquals(result.getTenantId(), TENANT_A);
            results2.contains(result);
        }
    }

    @Test
    public void testGlobMatching3() throws Exception {
        List<SearchResult> results = elasticIO.search(TENANT_A, "one.two.three0[01].fourA.five0");
        assertEquals(2, results.size());
        for (SearchResult result : results) {
            Assert.assertTrue(result.getMetricName().equals("one.two.three00.fourA.five0") || result.getMetricName().equals("one.two.three01.fourA.five0"));
        }
    }

    @Test
    public void testDeDupMetrics() throws Exception {
        // New index name and the locator to be written to it
        String ES_DUP = ElasticIO.ELASTICSEARCH_INDEX_NAME_WRITE + "_2";
        Locator testLocator = createTestLocator(TENANT_A, 0, "A", 0);
        // Metric is aleady there in old
        List<SearchResult> results = elasticIO.search(TENANT_A, testLocator.getMetricName());
        assertEquals(results.size(), 1);
        assertEquals(results.get(0).getMetricName(), testLocator.getMetricName());
        // Actually create the new index
//        esSetup.execute(EsSetup.createIndex(ES_DUP)
//                .withSettings(EsSetup.fromClassPath("index_settings.json"))
//                .withMapping("metrics", EsSetup.fromClassPath("metrics_mapping.json")));
        // Insert metric into the new index
        elasticIO.setINDEX_NAME_WRITE(ES_DUP);
        ArrayList metricList = new ArrayList();
        metricList.add(new Metric(createTestLocator(TENANT_A, 0, "A", 0), 987654321L, 0, new TimeValue(1, TimeUnit.DAYS), UNIT));
        elasticIO.insertDiscovery(metricList);
//        esSetup.client().admin().indices().prepareRefresh().execute().actionGet();
        // Set up aliases
//        esSetup.client().admin().indices().prepareAliases().addAlias(ES_DUP, "metric_metadata_read")
//                .addAlias(ElasticIO.ELASTICSEARCH_INDEX_NAME_WRITE, "metric_metadata_read").execute().actionGet();
        elasticIO.setINDEX_NAME_READ("metric_metadata_read");
        results = elasticIO.search(TENANT_A, testLocator.getMetricName());
        // Should just be one result
        assertEquals(results.size(), 1);
        assertEquals(results.get(0).getMetricName(), testLocator.getMetricName());
        elasticIO.setINDEX_NAME_READ(ElasticIOConfig.ELASTICSEARCH_INDEX_NAME_READ.getDefaultValue());
        elasticIO.setINDEX_NAME_WRITE(ElasticIOConfig.ELASTICSEARCH_INDEX_NAME_WRITE.getDefaultValue());
    }

    @Test
    public void testRegexLevel0() {
        List<String> terms = Arrays.asList("foo", "bar", "baz", "foo.bar", "foo.bar.baz", "foo.bar.baz.aux");

        List<String> matchingTerms = new ArrayList<>();
        Pattern patternToGet2Levels = Pattern.compile(elasticIO.regexToGrabCurrentAndNextLevel("*"));
        for (String term: terms) {
            Matcher matcher = patternToGet2Levels.matcher(term);
            if (matcher.matches()) {
                matchingTerms.add(term);
            }
        }

        assertEquals(1, matchingTerms.size());
        assertEquals("foo.bar", matchingTerms.get(0));
    }

    @Test
    public void testRegexLevel1() {
        List<String> terms = Arrays.asList("foo", "bar", "baz", "foo.bar", "foo.bar.baz", "foo.bar.baz.aux");

        List<String> matchingTerms = new ArrayList<String>();
        Pattern patternToGet2Levels = Pattern.compile(elasticIO.regexToGrabCurrentAndNextLevel("foo.*"));
        for (String term: terms) {
            Matcher matcher = patternToGet2Levels.matcher(term);
            if (matcher.matches()) {
                matchingTerms.add(term);
            }
        }

        assertEquals(2, matchingTerms.size());
        assertEquals("foo.bar", matchingTerms.get(0));
        assertEquals("foo.bar.baz", matchingTerms.get(1));
    }

    @Test
    public void testRegexLevel2() {
        List<String> terms = Arrays.asList("foo", "bar", "baz", "foo.bar", "foo.bar.baz", "foo.bar.baz.qux", "foo.bar.baz.qux.quux");

        List<String> matchingTerms = new ArrayList<String>();
        Pattern patternToGet2Levels = Pattern.compile(elasticIO.regexToGrabCurrentAndNextLevel("foo.bar.*"));
        for (String term: terms) {
            Matcher matcher = patternToGet2Levels.matcher(term);
            if (matcher.matches()) {
                matchingTerms.add(term);
            }
        }

        assertEquals(2, matchingTerms.size());
        assertEquals("foo.bar.baz", matchingTerms.get(0));
        assertEquals("foo.bar.baz.qux", matchingTerms.get(1));
    }

    @Test
    public void testRegexLevel3() {
        List<String> terms = Arrays.asList("foo", "bar", "baz", "foo.bar", "foo.bar.baz", "foo.bar.baz.qux", "foo.bar.baz.qux.quux");

        List<String> matchingTerms = new ArrayList<String>();
        Pattern patternToGet2Levels = Pattern.compile(elasticIO.regexToGrabCurrentAndNextLevel("foo.bar.baz.*"));
        for (String term: terms) {
            Matcher matcher = patternToGet2Levels.matcher(term);
            if (matcher.matches()) {
                matchingTerms.add(term);
            }
        }

        assertEquals(2, matchingTerms.size());
        assertEquals("foo.bar.baz.qux", matchingTerms.get(0));
        assertEquals("foo.bar.baz.qux.quux", matchingTerms.get(1));
    }
}
