/*
 * Copyright 2021 LinkedIn Corp. All rights reserved.
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
 *
 */

package com.github.ambry.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.ambry.commons.ByteBufferReadableStreamChannel;
import com.github.ambry.commons.Callback;
import com.github.ambry.commons.CallbackUtils;
import com.github.ambry.named.NamedBlobDb;
import com.github.ambry.named.NamedBlobRecord;
import com.github.ambry.rest.RestRequest;
import com.github.ambry.rest.RestRequestMetrics;
import com.github.ambry.rest.RestResponseChannel;
import com.github.ambry.rest.RestServiceErrorCode;
import com.github.ambry.rest.RestServiceException;
import com.github.ambry.rest.RestUtils;
import com.github.ambry.router.ReadableStreamChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.ambry.frontend.FrontendUtils.*;
import static com.github.ambry.rest.RestUtils.InternalKeys.*;


/**
 * Handles requests for listing blobs that exist in a named blob container that start with a provided prefix.
 */
public class NamedBlobListHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(NamedBlobListHandler.class);

  private final SecurityService securityService;
  private final NamedBlobDb namedBlobDb;
  private final AccountAndContainerInjector accountAndContainerInjector;
  private final FrontendMetrics frontendMetrics;

  /**
   * Constructs a handler for handling requests for listing blobs in named blob accounts.
   * @param securityService the {@link SecurityService} to use.
   * @param namedBlobDb the {@link NamedBlobDb} to use.
   * @param frontendMetrics {@link FrontendMetrics} instance where metrics should be recorded.
   */
  NamedBlobListHandler(SecurityService securityService, NamedBlobDb namedBlobDb,
      AccountAndContainerInjector accountAndContainerInjector, FrontendMetrics frontendMetrics) {
    this.securityService = securityService;
    this.namedBlobDb = namedBlobDb;
    this.accountAndContainerInjector = accountAndContainerInjector;
    this.frontendMetrics = frontendMetrics;
  }

  /**
   * Asynchronously get account metadata.
   * @param restRequest the {@link RestRequest} that contains the request parameters and body.
   * @param restResponseChannel the {@link RestResponseChannel} where headers should be set.
   * @param callback the {@link Callback} to invoke when the response is ready (or if there is an exception).
   */
  void handle(RestRequest restRequest, RestResponseChannel restResponseChannel,
      Callback<ReadableStreamChannel> callback) {
    new CallbackChain(restRequest, restResponseChannel, callback).start();
  }

  /**
   * Represents the chain of actions to take. Keeps request context that is relevant to all callback stages.
   */
  private class CallbackChain {
    private final RestRequest restRequest;
    private final String uri;
    private final RestResponseChannel restResponseChannel;
    private final Callback<ReadableStreamChannel> finalCallback;

    /**
     * @param restRequest the {@link RestRequest}.
     * @param restResponseChannel the {@link RestResponseChannel}.
     * @param finalCallback the {@link Callback} to call on completion.
     */
    private CallbackChain(RestRequest restRequest, RestResponseChannel restResponseChannel,
        Callback<ReadableStreamChannel> finalCallback) {
      this.restRequest = restRequest;
      this.restResponseChannel = restResponseChannel;
      this.finalCallback = finalCallback;
      this.uri = restRequest.getUri();
    }

    /**
     * Start the chain by calling {@link SecurityService#preProcessRequest}.
     */
    private void start() {
      try {
        RestRequestMetrics requestMetrics =
            frontendMetrics.getAccountsMetricsGroup.getRestRequestMetrics(restRequest.isSslUsed(), false);
        restRequest.getMetricsTracker().injectMetrics(requestMetrics);
        accountAndContainerInjector.injectAccountContainerForNamedBlob(restRequest,
            frontendMetrics.getBlobMetricsGroup);
        if (namedBlobDb == null) {
          throw new RestServiceException("Named blob support not enabled", RestServiceErrorCode.BadRequest);
        }
        // Start the callback chain by performing request security processing.
        securityService.processRequest(restRequest, securityProcessRequestCallback());
      } catch (Exception e) {
        finalCallback.onCompletion(null, e);
      }
    }

    /**
     * After {@link SecurityService#processRequest} finishes, call {@link SecurityService#postProcessRequest} to perform
     * any remaining security checks.
     * @return a {@link Callback} to be used with {@link SecurityService#processRequest}.
     */
    private Callback<Void> securityProcessRequestCallback() {
      return buildCallback(frontendMetrics.listSecurityProcessRequestMetrics,
          securityCheckResult -> securityService.postProcessRequest(restRequest, securityPostProcessRequestCallback()),
          uri, LOGGER, finalCallback);
    }

    /**
     * After {@link SecurityService#postProcessRequest} finishes, make a call to {@link NamedBlobDb#list}.
     * @return a {@link Callback} to be used with {@link SecurityService#postProcessRequest}.
     */
    private Callback<Void> securityPostProcessRequestCallback() {
      return buildCallback(frontendMetrics.listSecurityPostProcessRequestMetrics, securityCheckResult -> {
        NamedBlobPath namedBlobPath = NamedBlobPath.parse(RestUtils.getRequestPath(restRequest), restRequest.getArgs());
        CallbackUtils.callCallbackAfter(
            namedBlobDb.list(namedBlobPath.getAccountName(), namedBlobPath.getContainerName(),
                namedBlobPath.getBlobNamePrefix(), namedBlobPath.getPageToken()), listBlobsCallback());
      }, uri, LOGGER, finalCallback);
    }

    /**
     * After {@link NamedBlobDb#list} finishes, serialize the result to JSON and send the response.
     * @return a {@link Callback} to be used with {@link NamedBlobDb#list}.
     */
    private Callback<Page<NamedBlobRecord>> listBlobsCallback() {
      return buildCallback(frontendMetrics.listDbLookupMetrics, page -> {
        //ReadableStreamChannel channel =
        //    serializeJsonToChannel(page.toJson(record -> new NamedBlobListEntry(record).toJson()));

        // S3 expects listing of blobs in xml format.

        if (restRequest.getArgs().containsKey("uploadId")) {
          String bucket = (String) restRequest.getArgs().get(S3_BUCKET);
          String key = (String) restRequest.getArgs().get(S3_KEY);
          String uploadId = (String) restRequest.getArgs().get("uploadId");
          LOGGER.info(
              "NamedBlobListHandler | Sending response for list upload parts. Bucket = {}, Key = {}, Upload Id = {}",
              bucket, key, uploadId);
          ListPartsResult listPartsResult = new ListPartsResult();
          listPartsResult.setBucket(bucket);
          listPartsResult.setKey(key);
          listPartsResult.setUploadId(uploadId);
          ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
          ObjectMapper objectMapper = new XmlMapper();
          objectMapper.writeValue(outputStream, listPartsResult);
          ReadableStreamChannel channel =
              new ByteBufferReadableStreamChannel(ByteBuffer.wrap(outputStream.toByteArray()));
          restResponseChannel.setHeader(RestUtils.Headers.DATE, new GregorianCalendar().getTime());
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_TYPE, "application/xml");
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_LENGTH, channel.getSize());
          finalCallback.onCompletion(channel, null);
        } else {
          ReadableStreamChannel channel = serializeAsXml(page);
          restResponseChannel.setHeader(RestUtils.Headers.DATE, new GregorianCalendar().getTime());
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_TYPE, "application/xml");
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_LENGTH, channel.getSize());
          finalCallback.onCompletion(channel, null);
        }
      }, uri, LOGGER, finalCallback);
    }

    private ReadableStreamChannel serializeAsXml(Page<NamedBlobRecord> namedBlobRecordPage) throws IOException {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

      ListBucketResult listBucketResult = new ListBucketResult();
      listBucketResult.setName(restRequest.getPath());
      listBucketResult.setPrefix(restRequest.getArgs().get("prefix").toString());
      listBucketResult.setMaxKeys(1);
      listBucketResult.setDelimiter("/");
      listBucketResult.setEncodingType("url");

      List<Contents> contentsList = new ArrayList<>();
      List<NamedBlobRecord> namedBlobRecords = namedBlobRecordPage.getEntries();
      for (NamedBlobRecord namedBlobRecord : namedBlobRecords) {
        Contents contents = new Contents();
        contents.setKey(namedBlobRecord.getBlobName());
        String todayDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(Calendar.getInstance().getTime());
        contents.setLastModified(todayDate);
        contentsList.add(contents);
      }

      listBucketResult.setContents(contentsList);

      ObjectMapper objectMapper = new XmlMapper();
      objectMapper.writeValue(outputStream, listBucketResult);

      return new ByteBufferReadableStreamChannel(ByteBuffer.wrap(outputStream.toByteArray()));
    }
  }
}
