package com.grayscaleconsulting.bitacora.rpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.grayscaleconsulting.bitacora.data.DataManager;
import com.grayscaleconsulting.bitacora.data.metadata.KeyValue;

import com.grayscaleconsulting.bitacora.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import org.apache.http.HttpResponse;
import org.mortbay.jetty.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Creates an HTTP interface that exposes methods to share data with other nodes in the cluster as well as 
 * with consumers of this service
 *
 * Created by ivaramme on 7/3/15.
 */
public class HttpRPCHandler extends AbstractHandler {
    protected static final Logger logger = LoggerFactory.getLogger(HttpRPCHandler.class);

    public static final String RPC_ENDPOINT         = "/rpc";
    public static final String RPC_CLUSTER_ENDPOINT = "/rpc/cluster";

    private final Counter requests = Metrics.getDefault().newCounter(HttpRPCHandler.class, "rpc-http-requests");
    private final Counter clusterRequests = Metrics.getDefault().newCounter(HttpRPCHandler.class, "rpc-http-cluster-requests");
    private final Counter publicRequests = Metrics.getDefault().newCounter(HttpRPCHandler.class, "rpc-http-public-requests");

    private Gson jsonParser;
    private DataManager dataManager;
    
    public HttpRPCHandler(DataManager dataManager) {
        this.dataManager = dataManager;
        jsonParser = new GsonBuilder().create();
    }
    
    @Override
    public void handle(String endpoint, HttpServletRequest req, HttpServletResponse resp, int i) throws IOException, ServletException {
        if(endpoint.startsWith(RPC_ENDPOINT) || endpoint.startsWith(RPC_CLUSTER_ENDPOINT)) {
            logger.info("New request for endpoint: " + endpoint);
            requests.inc();
            
            try {
                if(null == dataManager) {
                    logger.error("RPC service is not configured correctly");
                    sendErrorResponse(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }

                // Get a value from
                if(req.getMethod().equalsIgnoreCase("GET")) {
                    // TODO: change this from being a param to being part of the URI
                    String key = req.getParameter("key");
                    if (null == key || key.isEmpty() || key.trim().isEmpty()) {
                        logger.info("Missing request information");
                        sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST);
                        resp.flushBuffer();
                        return;
                    }

                    // Forward request to cluster in case this is a normal RPC request. 
                    // If the request came from the cluster rpc endpoint, do not forward
                    boolean forwardRPCRequest = endpoint.equals(RPC_ENDPOINT);
                    KeyValue keyValue = dataManager.getRaw(key, forwardRPCRequest);
                    if (null == keyValue) {
                        logger.info(key + " was not found, returning null value");
                        clusterRequests.inc();
                        sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "{ \"message\": \"Key "+ key +" not found\"} ");
                    } else {
                        publicRequests.inc(); // public requests will always forward
                        sendResponse(keyValue, resp, forwardRPCRequest);
                    }
                } else
                // Set a new value
                if(req.getMethod().equalsIgnoreCase("POST")) {
                    logger.info("RPC: Creating a new key");
                    String key = req.getParameter("key");
                    String value = req.getParameter("value");
                    if ((null != key || !key.isEmpty() || !key.trim().isEmpty()) &&
                            (null != value || !value.isEmpty() || !value.trim().isEmpty())) {
                        dataManager.set(key, value);
                    }
                } else
                // Delete value
                if(req.getMethod().equalsIgnoreCase("DELETE")) {
                    logger.info("RPC: Deleting key");
                    String key = req.getParameter("key");
                    if (null != key || !key.isEmpty() || !key.trim().isEmpty()) {
                        dataManager.delete(key);
                    }
                } else {
                    logger.info("Unknown method for endpoint: " + endpoint + " -> " + req.getMethod());
                    sendErrorResponse(resp, HttpServletResponse.SC_NOT_IMPLEMENTED);
                }
            } catch (IOException ioe) {
                logger.error("Error sending response to request. ");
                ioe.printStackTrace();
            }
        }else {
            logger.info("Invalid endpoint: " + endpoint);
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_IMPLEMENTED);
        }
        resp.flushBuffer();
    }

    private void sendResponse(KeyValue value, HttpServletResponse resp, boolean forwardRequest) throws IOException {
        logger.info(value + " was found " + (!forwardRequest ? "in local cache returning to fellow node" : ""));

        resp.setContentType("application/json");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().println(jsonParser.toJson(value, KeyValue.class));
    }
    
    private void sendErrorResponse(HttpServletResponse resp, int code) throws IOException {
        sendErrorResponse(resp, code, "{ \"message\": \"Error: "+ code +"\"} ");
    }

    private void sendErrorResponse(HttpServletResponse resp, int code, String message) throws IOException {
        resp.setContentType("application/json");
        resp.setStatus(code);
        resp.getWriter().print(message);
    }
}
