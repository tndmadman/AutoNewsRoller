# Notifications and Automatic Phone Delivery

## Status

This is a proposed integration.

The current main pipeline does not yet automatically push a phone notification or transfer the finished MP4 to a phone.

The existing watch loop is a natural place to use this feature, but the cleaner design is to trigger delivery from the Java pipeline only after a video passes audit and is copied into final_videos.

## Desired workflow

Recommended final behavior:

    new story discovered
      -> verified
      -> video produced
      -> audit approved
      -> final MP4 saved
      -> transfer MP4 to phone
      -> confirm transfer command result
      -> push phone notification
      -> write delivery status into provenance/audit
      -> continue watch loop

The notification should be sent only for an approved output, not merely every time watch mode polls.

## Option A: KDE Connect for MP4 + ntfy for notification

This is the recommended combination for larger videos.

### Why KDE Connect

KDE Connect supports file sharing between desktop and phone.

Its command-line tooling supports a share action of the form:

    kdeconnect-cli -d <DEVICE_ID> --share "<FILE>"

KDE documentation includes examples using --share for sending files to a paired phone.

References:

https://kdeconnect.kde.org/

https://userbase.kde.org/KDE_Connect/Tutorials/Useful_commands

Windows KDE Connect can also send files from the desktop UI.

### Why pair it with ntfy

KDE Connect can surface transfer notifications itself, but ntfy gives AutoNewsRoller a separate structured push channel.

A notification could include:

- headline;
- source count;
- actual TTS engine;
- encoder;
- filename;
- transfer success/failure;
- batch/slot.

Example message concept:

    AutoNewsRoller — Video Approved

    Major story title here
    Sources: 4
    TTS: Kokoro
    Encoder: NVENC
    File: major_story.mp4
    Transfer: sent to phone

## Option B: ntfy attachment only

ntfy supports publishing a file attachment directly from a computer to a phone notification.

Official docs:

https://docs.ntfy.sh/publish/

Caution:

The current ntfy documentation distinguishes configurable/self-hosted limits from the public ntfy.sh service limits.

At the time this documentation was written, the limitations section reported a smaller public ntfy.sh attachment limit than the generic server default. Video files can easily exceed public-service limits.

Therefore, do not design 1080x1920 MP4 delivery around public ntfy attachment size unless current limits are verified.

For larger files, KDE Connect or a self-hosted/private file URL is more reliable.

## Topic privacy

Public ntfy topics should be treated like secrets.

The official ntfy docs note that an unprotected topic name is effectively the access secret.

Do not use a predictable topic such as:

    autonews

Use a long random topic or authenticated/self-hosted ntfy.

Do not commit private topic strings or authentication tokens into the public repository.

## Proposed configuration

Future defaults/config should support values like:

    phoneNotifyEnabled=false
    phoneTransferEnabled=false
    notificationProvider=ntfy
    ntfyBaseUrl=https://ntfy.sh
    ntfyTopicEnv=AUTONEWS_NTFY_TOPIC
    kdeConnectDeviceIdEnv=AUTONEWS_KDE_DEVICE_ID
    deliveryRetries=3
    notifyOnApproval=true
    notifyOnRepeatedFailure=true

Secrets/IDs should come from environment variables or a gitignored local config file.

## Proposed Java abstraction

Recommended interface:

    DeliveryResult deliver(Path finalVideo, DeliveryContext context)

Providers could be split into:

- PhoneTransferProvider
- NotificationProvider

Example implementations:

- KdeConnectTransferProvider
- NtfyNotificationProvider
- NtfyAttachmentProvider

The Java pipeline should call delivery after technical approval.

## Why not detect new MP4s by counting files?

A batch-file wrapper can compare the number of MP4s before and after a run.

That is workable for a quick prototype but weaker because:

- concurrent runs can confuse counts;
- an unrelated MP4 can be mistaken for output;
- transfer metadata is disconnected from the slot/story;
- a partial failure can produce ambiguous state;
- the exact approved file path is already known inside NewsPipeline.

The Java pipeline already knows finalVideo.

That is the correct object to deliver.

## Proposed delivery stages

Add structured stages such as:

- DELIVERY_TRANSFER
- DELIVERY_NOTIFY
- DELIVERY_COMPLETE
- DELIVERY_FAILED

The dashboard could then show:

    SLOT 004  DELIVERY_TRANSFER  Sending story_name.mp4
    SLOT 004  DELIVERY_NOTIFY    Notification sent
    SLOT 004  DELIVERY_COMPLETE  Phone delivery confirmed

## KDE Connect discovery

A setup helper should:

1. verify kdeconnect-cli exists;
2. list reachable/paired devices;
3. require an explicit device choice when multiple devices exist;
4. store only the selected device ID in local/private configuration.

Do not silently pick a random reachable device.

## Transfer behavior

Recommended logic:

1. run kdeconnect-cli with the final MP4;
2. capture exit code/stdout/stderr;
3. retry only transient failures;
4. record transfer start/end time;
5. send notification stating whether transfer succeeded;
6. never mark transfer successful based only on process launch.

A stronger future implementation could verify phone receipt if KDE Connect exposes a usable acknowledgment/status path.

## ntfy message behavior

Use a POST/PUT with explicit title and message.

Possible fields:

- title: AutoNewsRoller — Video Approved
- message: headline plus delivery details
- priority: normal/high
- tags: video/news
- click: optional local/cloud management URL when available

Avoid attaching sensitive logs or source data to a public ntfy topic.

## Failure notification policy

Recommended:

- approval + transfer success -> normal notification;
- approval + transfer failure -> high-priority notification;
- repeated pipeline failures -> notification after a threshold, not every single poll;
- no new story -> no notification.

This prevents notification spam in watch mode.

## Proposed failure threshold

Example policy:

- maintain consecutiveWatchFailures;
- reset to zero after an approved video;
- notify after 3 consecutive cycles that fail due to infrastructure;
- do not treat "no verified story available" as an infrastructure failure.

"No news passed verification" can be a healthy state.

## Delivery metadata

Add to final sidecar:

- phoneDeliveryAttempted;
- phoneDeliveryProvider;
- phoneDeliveryDeviceIdHash;
- phoneDeliveryStatus;
- phoneDeliveryTimestamp;
- notificationProvider;
- notificationStatus;
- notificationTimestamp;
- deliveryErrors.

Avoid storing raw secret tokens.

## Security

### Do not commit

- ntfy authentication token;
- predictable private topic;
- KDE-specific credentials if any;
- remote-access credentials;
- phone secrets.

### Prefer localhost/private LAN paths

KDE Connect is appropriate because it can transfer over the paired-device network relationship without uploading every MP4 to a public file host.

### Beware command injection

The final video path and notification text must be passed through ProcessBuilder argument arrays or safe HTTP libraries, not interpolated unsafely into shell commands.

The Java code already uses ProcessBuilder patterns for local helpers and should continue that approach.

## Quick batch-file prototype

Before the Java integration exists, a simple prototype can be inserted after a successful run.

Conceptually:

    call run_news_video_windows.bat
    if errorlevel 1 goto sleep

    determine newest approved final_videos MP4
    kdeconnect-cli -d "%KDE_DEVICE%" --share "%NEWVIDEO%"
    curl.exe ... ntfy ...

This should be considered temporary.

## Recommended implementation order

1. Add notification configuration.
2. Implement ntfy notification provider.
3. Add KDE Connect availability/device discovery helper.
4. Implement file transfer provider.
5. Call delivery after final audit/sidecar creation.
6. Add delivery state to events/dashboard.
7. Add delivery fields to sidecar.
8. Add unit tests using fake command runners/HTTP server.
9. Add watch-mode repeated-failure notification policy.
10. Perform a real Windows-to-Android transfer test.

## Phone-side setup

For KDE Connect:

- install KDE Connect on Windows;
- install KDE Connect on Android;
- pair the devices;
- allow file transfer/notification permissions required by the app;
- verify manual file sharing before automating it.

For ntfy:

- install the ntfy Android app;
- subscribe to the private/authenticated topic;
- verify a test message;
- confirm Android battery/background settings allow reliable notification delivery.

## Why this is not yet marked implemented

The repository currently has no built-in:

- kdeconnect-cli invocation;
- ntfy HTTP call;
- notification configuration;
- delivery audit fields;
- phone-device discovery.

The integration should be considered roadmap work until those code paths exist and a real phone receives an approved MP4.
