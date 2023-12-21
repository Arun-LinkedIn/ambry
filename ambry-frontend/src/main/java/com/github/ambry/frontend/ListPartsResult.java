/*
 * Copyright 2023 LinkedIn Corp. All rights reserved.
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

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;


public class ListPartsResult {

  @JacksonXmlProperty(localName = "Bucket")
  private String bucket;
  @JacksonXmlProperty(localName = "Key")
  private String key;
  @JacksonXmlProperty(localName = "UploadId")
  private String uploadId;

  public String getBucket() {
    return bucket;
  }

  public String getKey() {
    return key;
  }

  public String getUploadId() {
    return uploadId;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public void setUploadId(String uploadId) {
    this.uploadId = uploadId;
  }
}
