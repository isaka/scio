/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.beam.sdk.extensions.smb;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder.NonDeterministicException;

class TestBucketMetadata extends BucketMetadata<String, Void, String> {
  @JsonProperty("keyIndex")
  private Integer keyIndex = 0;

  static TestBucketMetadata of(int numBuckets, int numShards) {
    return of(numBuckets, numShards, SortedBucketIO.DEFAULT_FILENAME_PREFIX);
  }

  static TestBucketMetadata of(int numBuckets, int numShards, String filenamePrefix) {
    try {
      return new TestBucketMetadata(numBuckets, numShards, HashType.MURMUR3_32, filenamePrefix);
    } catch (CannotProvideCoderException | NonDeterministicException e) {
      throw new RuntimeException(e);
    }
  }

  TestBucketMetadata withKeyIndex(int keyIndex) {
    this.keyIndex = keyIndex;
    return this;
  }

  TestBucketMetadata(
      @JsonProperty("numBuckets") int numBuckets,
      @JsonProperty("numShards") int numShards,
      @JsonProperty("hashType") HashType hashType,
      @JsonProperty("filenamePrefix") String filenamePrefix)
      throws CannotProvideCoderException, NonDeterministicException {
    this(BucketMetadata.CURRENT_VERSION, numBuckets, numShards, hashType, filenamePrefix);
  }

  @JsonCreator
  TestBucketMetadata(
      @JsonProperty("version") int version,
      @JsonProperty("numBuckets") int numBuckets,
      @JsonProperty("numShards") int numShards,
      @JsonProperty("hashType") HashType hashType,
      @JsonProperty("filenamePrefix") String filenamePrefix)
      throws CannotProvideCoderException, NonDeterministicException {
    super(version, numBuckets, numShards, String.class, null, hashType, filenamePrefix);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestBucketMetadata metadata = (TestBucketMetadata) o;
    return this.keyIndex.equals(metadata.keyIndex)
        && this.getNumBuckets() == metadata.getNumBuckets()
        && this.getNumShards() == metadata.getNumShards()
        && this.getHashType() == metadata.getHashType();
  }

  @Override
  public int hashCode() {
    return Objects.hash(keyIndex, getNumBuckets(), getNumShards(), getHashType());
  }

  @Override
  public String extractKeyPrimary(final String value) {
    try {
      return value.substring(keyIndex, 1);
    } catch (StringIndexOutOfBoundsException e) {
      return null;
    }
  }

  @Override
  public Void extractKeySecondary(final String value) {
    throw new IllegalArgumentException();
  }

  @Override
  int hashPrimaryKeyMetadata() {
    return Objects.hash(getClass(), keyIndex);
  }

  @Override
  int hashSecondaryKeyMetadata() {
    throw new IllegalArgumentException();
  }
}
