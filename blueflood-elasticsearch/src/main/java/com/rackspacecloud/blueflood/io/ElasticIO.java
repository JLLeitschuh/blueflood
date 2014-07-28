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

import com.rackspacecloud.blueflood.service.ElasticClientManager;
import com.rackspacecloud.blueflood.service.RemoteElasticSearchServer;
import com.rackspacecloud.blueflood.types.Locator;
import com.rackspacecloud.blueflood.types.Metric;
import com.rackspacecloud.blueflood.utils.Metrics;
import com.rackspacecloud.blueflood.utils.Util;

import com.codahale.metrics.Timer;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rackspacecloud.blueflood.io.ElasticIO.ESFieldLabel.*;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.index.query.QueryBuilders.wildcardQuery;

public class ElasticIO implements DiscoveryIO {
    public static final String INDEX_NAME = "metric_metadata";
    
    static enum ESFieldLabel {
        METRIC_NAME,
        TENANT_ID,
        TYPE,
        UNIT
    }
    
    private static final Logger log = LoggerFactory.getLogger(DiscoveryIO.class);;
    private static final String ES_TYPE = "metrics";
    private final Client client;
    private final Timer searchTimer = Metrics.timer(ElasticIO.class, "Search Duration");

    public ElasticIO() {
        this(RemoteElasticSearchServer.getInstance());
    }

    public ElasticIO(Client client) {
        this.client = client;
    }

    public ElasticIO(ElasticClientManager manager) {
        this(manager.getClient());
    }

    private static SearchResult convertHitToMetricDiscoveryResult(SearchHit hit) {
        Map<String, Object> source = hit.getSource();
        String metricName = (String)source.get(METRIC_NAME.toString());
        String tenantId = (String)source.get(TENANT_ID.toString());
        String unit = (String)source.get(UNIT.toString());
        SearchResult result = new SearchResult(tenantId, metricName, unit);

        return result;
    }

    public void insertDiscovery(List<Metric> batch) throws IOException {
        // TODO: check bulk insert result and retry
        BulkRequestBuilder bulk = client.prepareBulk();
        for (Metric metric : batch) {
            Locator locator = metric.getLocator();
            Discovery md = new Discovery(locator.getTenantId(), locator.getMetricName());
            Map<String, Object> info = new HashMap<String, Object>();
            if (metric.getUnit() != null) { // metric units may be null
                info.put(UNIT.toString(), metric.getUnit());
            }
            info.put(TYPE.toString(), metric.getDataType());
            md.withAnnotation(info);
            bulk.add(createSingleRequest(md));
        }
        bulk.execute().actionGet();
    }

    private IndexRequestBuilder createSingleRequest(Discovery md) throws IOException {
        if (md.getMetricName() == null) {
            throw new IllegalArgumentException("trying to insert metric discovery without a metricName");
        }
        return client.prepareIndex(INDEX_NAME, ES_TYPE)
                .setId(md.getDocumentId())
                .setSource(md.createSourceContent())
                .setRouting(md.getTenantId());
    }
    
    public List<SearchResult> search(String tenant, String query) throws Exception {
        // complain if someone is trying to search specifically on any tenant.
        if (query.indexOf(TENANT_ID.name()) >= 0) {
            throw new Exception("Illegal query: " + query);
        }
        
        List<SearchResult> results = new ArrayList<SearchResult>();
        Timer.Context searchTimerCtx = searchTimer.time();
        
        // todo: we'll want to change this once we decide and use a query syntax in the query string.
        BoolQueryBuilder qb = boolQuery()
                .must(termQuery(TENANT_ID.toString(), tenant))
                .must(
                        query.contains("*") ?
                                wildcardQuery("RAW_METRIC_NAME", query) :
                                termQuery("RAW_METRIC_NAME", query)
                );     
        SearchResponse response = client.prepareSearch(INDEX_NAME)
                .setRouting(tenant)
                .setSize(500)
                .setVersion(true)
                .setQuery(qb)
                .execute()
                .actionGet();
        searchTimerCtx.stop();
        for (SearchHit hit : response.getHits().getHits()) {
            SearchResult result = convertHitToMetricDiscoveryResult(hit);
            results.add(result);
        }
        return results;
    }


    public static class Discovery {
        private Map<String, Object> annotation = new HashMap<String, Object>();
        private final String metricName;
        private final String tenantId;

        public Discovery(String tenantId, String metricName) {
            this.tenantId = tenantId;
            this.metricName = metricName;
        }
        public Map<String, Object> getAnnotation() {
            return annotation;
        }

        public String getTenantId() {
            return tenantId;
        }

        public String getMetricName() {
            return metricName;
        }

        public String getDocumentId() {
            return tenantId + ":" + metricName;
        }

        @Override
        public String toString() {
            return "ElasticMetricDiscovery [tenantId=" + tenantId + ", metricName=" + metricName + ", annotation="
                    + annotation.toString() + "]";
        }

        public Discovery withAnnotation(Map<String, Object> annotation) {
            this.annotation = annotation;
            return this;
        }

        private XContentBuilder createSourceContent() throws IOException {
            XContentBuilder json;

            json = XContentFactory.jsonBuilder().startObject()
                    .field(TENANT_ID.toString(), tenantId)
                    .field(METRIC_NAME.toString(), metricName);


            for (Map.Entry<String, Object> entry : annotation.entrySet()) {
                json = json.field(entry.getKey(), entry.getValue());
            }
            json = json.endObject();
            return json;
        }
    }
}
