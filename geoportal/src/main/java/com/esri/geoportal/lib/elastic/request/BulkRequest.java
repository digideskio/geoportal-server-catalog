/* See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * Esri Inc. licenses this file to You under the Apache License, Version 2.0
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
package com.esri.geoportal.lib.elastic.request;
import com.esri.geoportal.context.AppRequest;
import com.esri.geoportal.context.AppResponse;
import com.esri.geoportal.context.GeoportalContext;
import com.esri.geoportal.lib.elastic.ElasticContext;
import com.esri.geoportal.lib.elastic.util.AccessUtil;
import com.esri.geoportal.lib.elastic.util.Scroller;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bulk request.
 */
public class BulkRequest extends AppRequest {
  
  /** Logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(BulkRequest.class);
  
  /** Instance variables. */
  private boolean adminOnly = true;
  private int docsPerRequest = 1000;
  private String processMessage;
  private int retryOnConflict = 1;
  private int scrollerPageSize = 1000;
    
  /** Constructor. */
  public BulkRequest() {
    super();
  }
  
  /** Requires admin role if true. */
  public boolean getAdminOnly() {
    return adminOnly;
  }
  /** Requires admin role if true. */
  public void setAdminOnly(boolean adminOnly) {
    this.adminOnly = adminOnly;
  }

  /** The number of docs per bulk request. */
  public int getDocsPerRequest() {
    return docsPerRequest;
  }
  /** The number of docs per bulk request. */
  public void setDocsPerRequest(int docsPerRequest) {
    this.docsPerRequest = docsPerRequest;
  }
  
  /** The process message. */
  public String getProcessMessage() {
    return processMessage;
  }
  /** The process message. */
  public void setProcessMessage(String processMessage) {
    this.processMessage = processMessage;
  }
  
  /** How many retries for a version conflict. */
  public int getRetryOnConflict() {
    return retryOnConflict;
  }
  /** How many retries for a version conflict. */
  public void setRetryOnConflict(int retryOnConflict) {
    this.retryOnConflict = retryOnConflict;
  }

  /** The scroller page size. */
  public int getScrollerPageSize() {
    return scrollerPageSize;
  }
  /** The scroller page size. */
  public void setScrollerPageSize(int scrollerPageSize) {
    this.scrollerPageSize = scrollerPageSize;
  }
  
  /** Methods =============================================================== */
  
  /**
   * Append the scroller hit to the buld request.
   * @param ec the Elastic context
   * @param request the request
   * @param hit the hit
   */
  protected void appendHit(ElasticContext ec, BulkRequestBuilder request, SearchHit hit) {}
  
  @Override
  public AppResponse execute() throws Exception {
    AppResponse response = new AppResponse();
    ElasticContext ec = GeoportalContext.getInstance().getElasticContext();
    AccessUtil au = new AccessUtil();
    if (getAdminOnly()) {
      au.ensureAdmin(getUser());
    } 
    long tStart = System.currentTimeMillis();
    AtomicLong count = new AtomicLong();
    AtomicLong loopCount = new AtomicLong();
    int docsPerRequest = getDocsPerRequest();
    Scroller scroller = newScroller(ec);
    scroller.scroll(
      new Consumer<SearchHit>(){
        BulkRequestBuilder bulkRequest = ec.getTransportClient().prepareBulk();
        @Override
        public void accept(SearchHit hit) {
          try {
            count.incrementAndGet();
            loopCount.incrementAndGet();
            boolean last = isLast();
            appendHit(ec,bulkRequest,hit);
            if (last || loopCount.get() >= docsPerRequest) {
              // TODO what about partial failures??
              bulkRequest.get();
              loopCount.set(0);
              bulkRequest = ec.getTransportClient().prepareBulk();
              logFeedback(tStart,count.get(),scroller.getTotalHits(),last);
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
        public boolean isLast() {
          return count.get() >= scroller.getTotalHits() || count.get() >= scroller.getMaxDocs();
        }
      }
    );
    
    writeOk(response,count.get());
    return response;
  }
    
  /**
   * Log feedback.
   * @param tStart the start time
   * @param count the item count
   * @param total the total number of items
   * @param finished true if the job has finished
   */
  protected void logFeedback(long tStart, long count, long total, boolean finished) {
    String msg = getProcessMessage();
    long tEnd = System.currentTimeMillis();
    double tSec = (tEnd - tStart) / 1000.0;
    if (finished) msg = "Finished "+msg;
    String tMsg = (Math.round(tSec / 60.0 * 100.0) / 100.0)+" minutes";
    if (tSec <= 600) tMsg = (Math.round(tSec * 100.0) / 100.0)+" seconds";
    String nMsg = count+" of "+total;
    LOGGER.debug(msg+", "+tMsg+", "+nMsg);
  }
  
  /**
   * Create the scroller.
   * @param ec the Elastic context
   * @return the scroller
   */
  protected Scroller newScroller(ElasticContext ec) {
    return null;
  }
  
  /**
   * Write the response. 
   * @param response the response
   * @param id the item id
   */
  public void writeOk(AppResponse response, long count) {
    JsonObjectBuilder jsonBuilder = Json.createObjectBuilder();
    jsonBuilder.add("count",count);
    jsonBuilder.add("status","updated");
    response.writeOkJson(this,jsonBuilder);
  }
  
}
