# Introduction

> *"Σκόπει εἰς σεαυτόν. Ἐντὸς σοῦ πηγὴ ἀγαθοῦ ἐστιν, ἡ ἀεὶ ἐκβρύειν ἑτοίμη, ἐὰν ἀεὶ σκάπτῃς."*
>
> "Look within. Within is the fountain of good, and it will ever bubble up, if you will ever dig."

*- Marcus Aurelius (121-180 AD), Roman Emperor and Stoic philosopher*

> *"Ignis aurum probat, miseria fortes."*
>
> "Fire tests gold and adversity tests the brave."

*-Seneca the Younger (c. 4 BC - AD 65), Roman statesman and Stoic philosopher*

<br>

Stoic lets you look within your Android processes, giving you the courage to
take on difficult bugs.

Stoic enables plugins that run inside of a process and examine its
internals. It leverages
[JVMTI](https://en.wikipedia.org/wiki/Java_Virtual_Machine_Tools_Interface)
to attach to any debuggable process, without any modifications to the APK. It
exposes JVMTI capabilities to Java/Kotlin, so you can do things like get a list
of every instance of a particular class currently in the heap.

# Example
```
% stoic --pkg com.example appexitinfo
epoch           timestamp             process-name   reason           importance
-------------   -------------------   ------------   --------------   ----------
1716541377356   2024-05-24 02:02:57   com.example    USER_REQUESTED   FOREGROUND
1716541221272   2024-05-24 02:00:21   com.example    USER_REQUESTED   FOREGROUND
1716540755344   2024-05-24 01:52:35   com.example    USER_REQUESTED   FOREGROUND
1716540607276   2024-05-24 01:50:07   com.example    USER_REQUESTED   FOREGROUND
1716539739494   2024-05-24 01:35:39   com.example    USER_REQUESTED   FOREGROUND
1716539111929   2024-05-24 01:25:11   com.example    USER_REQUESTED   FOREGROUND
1716538807562   2024-05-24 01:20:07   com.example    USER_REQUESTED   FOREGROUND
1716538786337   2024-05-24 01:19:46   com.example    USER_REQUESTED   FOREGROUND
1716538138670   2024-05-24 01:08:58   com.example    ANR              FOREGROUND
1716537817155   2024-05-24 01:03:37   com.example    USER_REQUESTED   FOREGROUND
1716537775343   2024-05-24 01:02:55   com.example    USER_REQUESTED   FOREGROUND
1715012315539   2024-05-06 09:18:35   com.example    OTHER            CACHED
1714188268641   2024-04-26 20:24:28   com.example    OTHER            CACHED
```
This calls ActivityManager.getHistoricalProcessExitReasons from within
`com.example`'s process (without any Stoic-related code integrated into the
`com.example` APK) and prints the results (including an epoch timestamp) to
stdout. You can then get additional information about a particular exit with
`--epoch`. Example:
```
% stoic --pkg com.example appexitinfo --epoch 1716538138670
1716538138670 (2024-05-24 01:08:58) com.example ANR FOREGROUND
Subject: Input dispatching timed out (77cb36a com.example/com.example.LoggedOutActivity (server) is not responding. Waited 5006ms for MotionEvent(deviceId=3, eventTime=1687386880000, source=TOUCHSCREEN | STYLUS, displayId=0, action=DOWN, actionButton=0x00000000, flags=0x00000000, metaState=0x00000000, buttonState=0x00000000, classification=NONE, edgeFlags=0x00000000, xPrecision=12.8, yPrecision=20.5, xCursorPosition=nan, yCursorPosition=nan, pointers=[0: (2062.9, 812.0)]), policyFlags=0x62000000)
RssHwmKb: 561152
RssKb: 551032
RssAnonKb: 447692
RssShmemKb: 1356
VmSwapKb: 0


--- CriticalEventLog ---
capacity: 20
timestamp_ms: 1716538132953
window_ms: 300000

----- dumping pid: 8240 at 1692406

----- pid 8240 at 2024-05-24 01:08:52.971875968-0700 -----
Cmd line: com.example
Build fingerprint: 'google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036.A1/11228894:userdebug/dev-keys'
ABI: 'arm64'
Build type: optimized
suspend all histogram:	Sum: 337us 99% C.I. 0.092us-122.879us Avg: 7.659us Max: 130us
DALVIK THREADS (73):
"main" prio=5 tid=1 Blocked
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x71bf71c8 self=0xb400007990a40f50
  | sysTid=8240 nice=-10 cgrp=top-app sched=0/0 handle=0x7b3b78e4f8
  | state=S schedstat=( 2383449141 129981627 944 ) utm=224 stm=14 core=0 HZ=100
  | stack=0x7ff9714000-0x7ff9716000 stackSize=8188KB
  | held mutexes=
  at MainKt.injectAnr$lambda$6(Main.kt:51)
  - waiting to lock <0x0253589a> (a java.lang.Object) held by thread 22
  - locked <0x0397d5cb> (a java.lang.Object)
  at MainKt.$r8$lambda$rlUPJ5n02yVEmNeC3rFef67j_mg(unavailable:0)
  at MainKt$$ExternalSyntheticLambda1.run(unavailable:8)
  at android.os.Handler.handleCallback(Handler.java:958)
  at android.os.Handler.dispatchMessage(Handler.java:99)
  at android.os.Looper.loopOnce(Looper.java:205)
  at android.os.Looper.loop(Looper.java:294)
  at android.app.ActivityThread.main(ActivityThread.java:8177)
  at java.lang.reflect.Method.invoke(Native method)
  at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:552)
  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)

"ANR Thread" daemon prio=1 tid=22 Blocked
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x13600750 self=0xb400007990cd8fb0
  | sysTid=8923 nice=0 cgrp=top-app sched=0/0 handle=0x784f499cb0
  | state=S schedstat=( 487210 36959 5 ) utm=0 stm=0 core=2 HZ=100
  | stack=0x784f396000-0x784f398000 stackSize=1039KB
  | held mutexes=
  at MainKt.injectAnr$lambda$3$lambda$2(Main.kt:41)
  - waiting to lock <0x0397d5cb> (a java.lang.Object) held by thread 1
  - locked <0x0253589a> (a java.lang.Object)
  at MainKt.$r8$lambda$Z0QSxIY0MznwMokxVXsvpJGVa9s(unavailable:0)
  at MainKt$$ExternalSyntheticLambda0.run(unavailable:8)
  at android.os.Handler.handleCallback(Handler.java:958)
  at android.os.Handler.dispatchMessage(Handler.java:99)
  at android.os.Looper.loopOnce(Looper.java:205)
  at android.os.Looper.loop(Looper.java:294)
  at android.os.HandlerThread.run(HandlerThread.java:67)
...
```


# Architecture

The first time you run Stoic on a process it will attach a jvmti agent which
will start a server running inside the process. We connect to the server
through named pipes (i.e. `mkfifo`). 

The server multiplexes stdin/stdout/stderr between each tty and the plugin its
running.
