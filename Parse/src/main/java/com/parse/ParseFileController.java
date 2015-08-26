/*
 * Copyright (c) 2015-present, Parse, LLC.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.parse;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import bolts.Continuation;
import bolts.Task;

// TODO(grantland): Create ParseFileController interface
/** package */ class ParseFileController {

  private final Object lock = new Object();
  private final ParseHttpClient restClient;
  private final File cachePath;

  private ParseHttpClient awsClient;

  public ParseFileController(ParseHttpClient restClient, File cachePath) {
    this.restClient = restClient;
    this.cachePath = cachePath;
  }

  /**
   * Gets the AWS http client if exists, otherwise lazily creates since developers might not always
   * use our download mechanism.
   */
  /* package */ ParseHttpClient awsClient() {
    synchronized (lock) {
      if (awsClient == null) {
        awsClient = ParsePlugins.get().newHttpClient();
      }
      return awsClient;
    }
  }

  /* package for tests */ ParseFileController awsClient(ParseHttpClient awsClient) {
    synchronized (lock) {
      this.awsClient = awsClient;
    }
    return this;
  }

  public File getCacheFile(ParseFile.State state) {
    return new File(cachePath, state.name());
  }

  public boolean isDataAvailable(ParseFile.State state) {
    return getCacheFile(state).exists();
  }

  public void clearCache() {
    File[] files = cachePath.listFiles();
    if (files == null) {
      return;
    }
    for (File file : files) {
      ParseFileUtils.deleteQuietly(file);
    }
  }

  public Task<ParseFile.State> saveAsync(
      final ParseFile.State state,
      final byte[] data,
      String sessionToken,
      ProgressCallback uploadProgressCallback,
      Task<Void> cancellationToken) {
    if (state.url() != null) { // !isDirty
      return Task.forResult(state);
    }
    if (cancellationToken != null && cancellationToken.isCancelled()) {
      return Task.cancelled();
    }

    final ParseRESTCommand command = new ParseRESTFileCommand.Builder()
        .fileName(state.name())
        .data(data)
        .contentType(state.mimeType())
        .sessionToken(sessionToken)
        .build();
    command.enableRetrying();

    return command.executeAsync(
        restClient,
        uploadProgressCallback,
        null,
        cancellationToken
    ).onSuccess(new Continuation<JSONObject, ParseFile.State>() {
      @Override
      public ParseFile.State then(Task<JSONObject> task) throws Exception {
        JSONObject result = task.getResult();
        ParseFile.State newState = new ParseFile.State.Builder(state)
            .name(result.getString("name"))
            .url(result.getString("url"))
            .build();

        // Write data to cache
        try {
          ParseFileUtils.writeByteArrayToFile(getCacheFile(newState), data);
        } catch (IOException e) {
          // do nothing
        }

        return newState;
      }
    }, ParseExecutors.io());
  }

  public Task<ParseFile.State> saveAsync(
      final ParseFile.State state,
      final File file,
      String sessionToken,
      ProgressCallback uploadProgressCallback,
      Task<Void> cancellationToken) {
    if (state.url() != null) { // !isDirty
      return Task.forResult(state);
    }
    if (cancellationToken != null && cancellationToken.isCancelled()) {
      return Task.cancelled();
    }

    final ParseRESTCommand command = new ParseRESTFileCommand.Builder()
        .fileName(state.name())
        .file(file)
        .contentType(state.mimeType())
        .sessionToken(sessionToken)
        .build();
    command.enableRetrying();

    return command.executeAsync(
        restClient,
        uploadProgressCallback,
        null,
        cancellationToken
    ).onSuccess(new Continuation<JSONObject, ParseFile.State>() {
      @Override
      public ParseFile.State then(Task<JSONObject> task) throws Exception {
        JSONObject result = task.getResult();
        ParseFile.State newState = new ParseFile.State.Builder(state)
            .name(result.getString("name"))
            .url(result.getString("url"))
            .build();

        // Write data to cache
        try {
          ParseFileUtils.copyFile(file, getCacheFile(newState));
        } catch (IOException e) {
          // do nothing
        }

        return newState;
      }
    }, ParseExecutors.io());
  }

  public Task<File> fetchAsync(
      final ParseFile.State state,
      @SuppressWarnings("UnusedParameters") String sessionToken,
      final ProgressCallback downloadProgressCallback,
      final Task<Void> cancellationToken) {
    if (cancellationToken != null && cancellationToken.isCancelled()) {
      return Task.cancelled();
    }
    final File file = getCacheFile(state);
    return Task.call(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return file.exists();
      }
    }, Task.BACKGROUND_EXECUTOR).continueWithTask(new Continuation<Boolean, Task<File>>() {
      @Override
      public Task<File> then(Task<Boolean> task) throws Exception {
        boolean result = task.getResult();
        if (result) {
          return Task.forResult(file);
        }
        if (cancellationToken != null && cancellationToken.isCancelled()) {
          return Task.cancelled();
        }

        // network
        final ParseAWSRequest request = new ParseAWSRequest(ParseHttpRequest.Method.GET, state.url());

        // TODO(grantland): Stream response directly to file t5042019
        return request.executeAsync(
            awsClient(),
            null,
            downloadProgressCallback,
            cancellationToken).onSuccess(new Continuation<byte[], File>() {
          @Override
          public File then(Task<byte[]> task) throws Exception {
            // If the top-level task was cancelled, don't actually set the data -- just move on.
            if (cancellationToken != null && cancellationToken.isCancelled()) {
              throw new CancellationException();
            }

            byte[] data = task.getResult();
            if (data != null) {
              ParseFileUtils.writeByteArrayToFile(file, data);
            }
            return file;
          }
        });
      }
    });
  }
}
