// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.firebasedynamiclinks;

import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.dynamiclinks.DynamicLink;
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks;
import com.google.firebase.dynamiclinks.PendingDynamicLinkData;
import com.google.firebase.dynamiclinks.ShortDynamicLink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.NewIntentListener;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FirebaseDynamicLinksPlugin */
public class FirebaseDynamicLinksPlugin implements MethodCallHandler, NewIntentListener {
  private final Registrar registrar;
  private final MethodChannel channel;

  private FirebaseDynamicLinksPlugin(Registrar registrar, MethodChannel channel) {
    this.registrar = registrar;
    this.channel = channel;
  }

  @Override
  public boolean onNewIntent(Intent intent) {
    FirebaseDynamicLinks.getInstance()
        .getDynamicLink(intent)
        .addOnSuccessListener(
            new OnSuccessListener<PendingDynamicLinkData>() {
              @Override
              public void onSuccess(PendingDynamicLinkData pendingDynamicLinkData) {
                if (pendingDynamicLinkData != null) {
                  Map<String, Object> dynamicLink =
                      getMapFromPendingDynamicLinkData(pendingDynamicLinkData);
                  channel.invokeMethod("onLinkSuccess", dynamicLink);
                }
              }
            })
        .addOnFailureListener(
            new OnFailureListener() {
              @Override
              public void onFailure(@NonNull Exception e) {
                Map<String, Object> exception = new HashMap<>();
                exception.put("code", e.getClass().getSimpleName());
                exception.put("message", e.getMessage());
                exception.put("details", null);
                channel.invokeMethod("onLinkError", exception);
              }
            });

    return false;
  }

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel =
        new MethodChannel(registrar.messenger(), "plugins.flutter.io/firebase_dynamic_links");
    final FirebaseDynamicLinksPlugin plugin = new FirebaseDynamicLinksPlugin(registrar, channel);
    registrar.addNewIntentListener(plugin);
    channel.setMethodCallHandler(plugin);
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    switch (call.method) {
      case "DynamicLinkParameters#buildUrl":
        DynamicLink.Builder urlBuilder = setupParameters(call);
        result.success(urlBuilder.buildDynamicLink().getUri().toString());
        break;
      case "DynamicLinkParameters#buildShortLink":
        DynamicLink.Builder shortLinkBuilder = setupParameters(call);
        buildShortDynamicLink(shortLinkBuilder, call, createShortLinkListener(result));
        break;
      case "DynamicLinkParameters#shortenUrl":
        DynamicLink.Builder builder = FirebaseDynamicLinks.getInstance().createDynamicLink();

        Uri url = Uri.parse((String) call.argument("url"));
        builder.setLongLink(url);
        buildShortDynamicLink(builder, call, createShortLinkListener(result));
        break;
      case "FirebaseDynamicLinks#getInitialLink":
        handleGetInitialDynamicLink(result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private Map<String, Object> getMapFromPendingDynamicLinkData(
      PendingDynamicLinkData pendingDynamicLinkData) {
    Map<String, Object> dynamicLink = new HashMap<>();
    dynamicLink.put("link", pendingDynamicLinkData.getLink().toString());

    Map<String, Object> androidData = new HashMap<>();
    androidData.put("clickTimestamp", pendingDynamicLinkData.getClickTimestamp());
    androidData.put("minimumVersion", pendingDynamicLinkData.getMinimumAppVersion());

    dynamicLink.put("android", androidData);
    return dynamicLink;
  }

  private void handleGetInitialDynamicLink(final Result result) {
    // If there's no activity, then there's no initial dynamic link.
    if (registrar.activity() == null) {
      result.success(null);
      return;
    }

    FirebaseDynamicLinks.getInstance()
        .getDynamicLink(registrar.activity().getIntent())
        .addOnSuccessListener(
            registrar.activity(),
            new OnSuccessListener<PendingDynamicLinkData>() {
              @Override
              public void onSuccess(PendingDynamicLinkData pendingDynamicLinkData) {
                if (pendingDynamicLinkData != null) {
                  Map<String, Object> dynamicLink =
                      getMapFromPendingDynamicLinkData(pendingDynamicLinkData);
                  result.success(dynamicLink);
                  return;
                }
                result.success(null);
              }
            })
        .addOnFailureListener(
            registrar.activity(),
            new OnFailureListener() {
              @Override
              public void onFailure(@NonNull Exception e) {
                result.error(e.getClass().getSimpleName(), e.getMessage(), null);
              }
            });
  }

  private OnCompleteListener<ShortDynamicLink> createShortLinkListener(final Result result) {
    return new OnCompleteListener<ShortDynamicLink>() {
      @Override
      public void onComplete(@NonNull Task<ShortDynamicLink> task) {
        if (task.isSuccessful()) {
          Map<String, Object> url = new HashMap<>();
          url.put("url", task.getResult().getShortLink().toString());

          List<String> warnings = new ArrayList<>();
          if (task.getResult().getWarnings() != null) {
            for (ShortDynamicLink.Warning warning : task.getResult().getWarnings()) {
              warnings.add(warning.getMessage());
            }
          }

          url.put("warnings", warnings);

          result.success(url);
        } else {
          Exception exception = task.getException();
          String errMsg = "Unable to create short link";
          if (exception != null && exception.getLocalizedMessage() != null) {
            errMsg = exception.getLocalizedMessage();
          }
          result.error("short_link_error", errMsg, null);
        }
      }
    };
  }

  private void buildShortDynamicLink(
      DynamicLink.Builder builder, MethodCall call, OnCompleteListener<ShortDynamicLink> listener) {
    Integer suffix = null;

    Map<String, Object> dynamicLinkParametersOptions =
        call.argument("dynamicLinkParametersOptions");
    if (dynamicLinkParametersOptions != null) {
      Integer shortDynamicLinkPathLength =
          (Integer) dynamicLinkParametersOptions.get("shortDynamicLinkPathLength");
      if (shortDynamicLinkPathLength != null) {
        switch (shortDynamicLinkPathLength) {
          case 0:
            suffix = ShortDynamicLink.Suffix.UNGUESSABLE;
            break;
          case 1:
            suffix = ShortDynamicLink.Suffix.SHORT;
            break;
          default:
            break;
        }
      }
    }

    if (suffix != null) {
      builder.buildShortDynamicLink(suffix).addOnCompleteListener(listener);
    } else {
      builder.buildShortDynamicLink().addOnCompleteListener(listener);
    }
  }

  private DynamicLink.Builder setupParameters(MethodCall call) {
    DynamicLink.Builder dynamicLinkBuilder = FirebaseDynamicLinks.getInstance().createDynamicLink();

    String uriPrefix = call.argument("uriPrefix");
    String link = call.argument("link");

    dynamicLinkBuilder.setDomainUriPrefix(uriPrefix);
    dynamicLinkBuilder.setLink(Uri.parse(link));

    Map<String, Object> androidParameters = call.argument("androidParameters");
    if (androidParameters != null) {
      String packageName = valueFor("packageName", androidParameters);
      String fallbackUrl = valueFor("fallbackUrl", androidParameters);
      Integer minimumVersion = valueFor("minimumVersion", androidParameters);

      DynamicLink.AndroidParameters.Builder builder =
          new DynamicLink.AndroidParameters.Builder(packageName);

      if (fallbackUrl != null) builder.setFallbackUrl(Uri.parse(fallbackUrl));
      if (minimumVersion != null) builder.setMinimumVersion(minimumVersion);

      dynamicLinkBuilder.setAndroidParameters(builder.build());
    }

    Map<String, Object> googleAnalyticsParameters = call.argument("googleAnalyticsParameters");
    if (googleAnalyticsParameters != null) {
      String campaign = valueFor("campaign", googleAnalyticsParameters);
      String content = valueFor("content", googleAnalyticsParameters);
      String medium = valueFor("medium", googleAnalyticsParameters);
      String source = valueFor("source", googleAnalyticsParameters);
      String term = valueFor("term", googleAnalyticsParameters);

      DynamicLink.GoogleAnalyticsParameters.Builder builder =
          new DynamicLink.GoogleAnalyticsParameters.Builder();

      if (campaign != null) builder.setCampaign(campaign);
      if (content != null) builder.setContent(content);
      if (medium != null) builder.setMedium(medium);
      if (source != null) builder.setSource(source);
      if (term != null) builder.setTerm(term);

      dynamicLinkBuilder.setGoogleAnalyticsParameters(builder.build());
    }

    Map<String, Object> iosParameters = call.argument("iosParameters");
    if (iosParameters != null) {
      String bundleId = valueFor("bundleId", iosParameters);
      String appStoreId = valueFor("appStoreId", iosParameters);
      String customScheme = valueFor("customScheme", iosParameters);
      String fallbackUrl = valueFor("fallbackUrl", iosParameters);
      String ipadBundleId = valueFor("ipadBundleId", iosParameters);
      String ipadFallbackUrl = valueFor("ipadFallbackUrl", iosParameters);
      String minimumVersion = valueFor("minimumVersion", iosParameters);

      DynamicLink.IosParameters.Builder builder = new DynamicLink.IosParameters.Builder(bundleId);

      if (appStoreId != null) builder.setAppStoreId(appStoreId);
      if (customScheme != null) builder.setCustomScheme(customScheme);
      if (fallbackUrl != null) builder.setFallbackUrl(Uri.parse(fallbackUrl));
      if (ipadBundleId != null) builder.setIpadBundleId(ipadBundleId);
      if (ipadFallbackUrl != null) builder.setIpadFallbackUrl(Uri.parse(ipadFallbackUrl));
      if (minimumVersion != null) builder.setMinimumVersion(minimumVersion);

      dynamicLinkBuilder.setIosParameters(builder.build());
    }

    Map<String, Object> itunesConnectAnalyticsParameters =
        call.argument("itunesConnectAnalyticsParameters");
    if (itunesConnectAnalyticsParameters != null) {
      String affiliateToken = valueFor("affiliateToken", itunesConnectAnalyticsParameters);
      String campaignToken = valueFor("campaignToken", itunesConnectAnalyticsParameters);
      String providerToken = valueFor("providerToken", itunesConnectAnalyticsParameters);

      DynamicLink.ItunesConnectAnalyticsParameters.Builder builder =
          new DynamicLink.ItunesConnectAnalyticsParameters.Builder();

      if (affiliateToken != null) builder.setAffiliateToken(affiliateToken);
      if (campaignToken != null) builder.setCampaignToken(campaignToken);
      if (providerToken != null) builder.setProviderToken(providerToken);

      dynamicLinkBuilder.setItunesConnectAnalyticsParameters(builder.build());
    }

    Map<String, Object> navigationInfoParameters = call.argument("navigationInfoParameters");
    if (navigationInfoParameters != null) {
      Boolean forcedRedirectEnabled = valueFor("forcedRedirectEnabled", navigationInfoParameters);

      DynamicLink.NavigationInfoParameters.Builder builder =
          new DynamicLink.NavigationInfoParameters.Builder();

      if (forcedRedirectEnabled != null) builder.setForcedRedirectEnabled(forcedRedirectEnabled);

      dynamicLinkBuilder.setNavigationInfoParameters(builder.build());
    }

    Map<String, Object> socialMetaTagParameters = call.argument("socialMetaTagParameters");
    if (socialMetaTagParameters != null) {
      String description = valueFor("description", socialMetaTagParameters);
      String imageUrl = valueFor("imageUrl", socialMetaTagParameters);
      String title = valueFor("title", socialMetaTagParameters);

      DynamicLink.SocialMetaTagParameters.Builder builder =
          new DynamicLink.SocialMetaTagParameters.Builder();

      if (description != null) builder.setDescription(description);
      if (imageUrl != null) builder.setImageUrl(Uri.parse(imageUrl));
      if (title != null) builder.setTitle(title);

      dynamicLinkBuilder.setSocialMetaTagParameters(builder.build());
    }

    return dynamicLinkBuilder;
  }

  private static <T> T valueFor(String key, Map<String, Object> map) {
    @SuppressWarnings("unchecked")
    T result = (T) map.get(key);
    return result;
  }
}
