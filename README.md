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
id        timestamp             process-name   reason           sub-reason   importance
-------   -------------------   ------------   --------------   ----------   ----------
R/j8rML   2024-06-05 13:37:36   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j8YpU   2024-06-05 13:36:24   com.example    ANR              UNKNOWN      FOREGROUND
R/j8PXn   2024-06-05 13:35:46   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j883J   2024-06-05 13:34:43   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j7cfu   2024-06-05 13:32:18   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j7b9q   2024-06-05 13:32:12   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j7EH_   2024-06-05 13:30:46   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j78HJ   2024-06-05 13:30:22   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/j6aBx   2024-06-05 13:27:46   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/iU4mq   2024-06-05 10:22:15   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/iT+4+   2024-06-05 10:21:48   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/iTTkH   2024-06-05 10:19:27   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/i19~F   2024-06-05 08:24:38   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/h/hs_   2024-06-05 08:22:27   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/f9hao   2024-06-04 19:02:53   com.example    USER_REQUESTED   FORCE_STOP   FOREGROUND
R/f9PHs   2024-06-04 19:01:39   com.example    CRASH_NATIVE     UNKNOWN      FOREGROUND
```
This calls ActivityManager.getHistoricalProcessExitReasons from within
`com.example`'s process (without any Stoic-related code integrated into the
`com.example` APK) and prints the results to stdout. You can then get
additional information about a particular exit with
`--id`. Example:
```
% stoic --pkg com.example appexitinfo --id R/j8YpU
Process Name: com.example
Timestamp: 2024-06-05 13:36:24 (epoch: 1717619784667)
Reason: ANR
Sub-reason: UNKNOWN
Importance: FOREGROUND
Description: user request after error: Input dispatching timed out (c694b07 com.example/com.example.ExampleActivity (server) is not responding. Waited 5008ms for MotionEvent(deviceId=3, eventTime=624650586000, source=TOUCHSCREEN | STYLUS, displayId=0, action=DOWN, actionButton=0x00000000, flags=0x00000000, metaState=0x00000000, buttonState=0x00000000, classification=NONE, edgeFlags=0x00000000, xPrecision=12.8, yPrecision=20.5, xCursorPosition=nan, yCursorPosition=nan, pointers=[0: (1194.9, 989.9)]), policyFlags=0x62000000)
Status: 0
Pss: 40496
Rss: 148456
Pid: 5645
Real Uid: 10192
Package Uid: 10192
Defining Uid: 10192
Package List: null
Process State Summary: null

Subject: Input dispatching timed out (c694b07 com.example/com.example.ExampleActivity (server) is not responding. Waited 5008ms for MotionEvent(deviceId=3, eventTime=624650586000, source=TOUCHSCREEN | STYLUS, displayId=0, action=DOWN, actionButton=0x00000000, flags=0x00000000, metaState=0x00000000, buttonState=0x00000000, classification=NONE, edgeFlags=0x00000000, xPrecision=12.8, yPrecision=20.5, xCursorPosition=nan, yCursorPosition=nan, pointers=[0: (1194.9, 989.9)]), policyFlags=0x62000000)
RssHwmKb: 146168
RssKb: 146164
RssAnonKb: 70484
RssShmemKb: 880
VmSwapKb: 0


--- CriticalEventLog ---
capacity: 20
timestamp_ms: 1717619782086
window_ms: 300000

----- dumping pid: 5645 at 629666

----- pid 5645 at 2024-06-05 13:36:22.075736591-0700 -----
Cmd line: com.example
Build fingerprint: 'google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036.A1/11228894:userdebug/dev-keys'
ABI: 'arm64'
Build type: optimized
suspend all histogram:	Sum: 1.778ms 99% C.I. 0.101us-658.880us Avg: 63.500us Max: 684us
DALVIK THREADS (22):
"main" prio=5 tid=1 Blocked
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x732ffdd8 self=0xb400006de4a196f0
  | sysTid=5645 nice=-10 cgrp=top-app sched=0/0 handle=0x6fce9a94f8
  | state=S schedstat=( 596731496 289036089 386 ) utm=48 stm=10 core=2 HZ=100
  | stack=0x7ff87d8000-0x7ff87da000 stackSize=8188KB
  | held mutexes=
  at MainKt.injectAnr$lambda$6(Main.kt:42)
  - waiting to lock <0x045af028> (a java.lang.Object) held by thread 3
  - locked <0x06d4a141> (a java.lang.Object)
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

"ANR Thread" daemon prio=1 tid=3 Blocked
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x132c1258 self=0xb400006de4a20630
  | sysTid=5829 nice=0 cgrp=top-app sched=0/0 handle=0x6cf36ffcb0
  | state=S schedstat=( 634586 44500 3 ) utm=0 stm=0 core=3 HZ=100
  | stack=0x6cf35fc000-0x6cf35fe000 stackSize=1039KB
  | held mutexes=
  at MainKt.injectAnr$lambda$3$lambda$2(Main.kt:32)
  - waiting to lock <0x06d4a141> (a java.lang.Object) held by thread 1
  - locked <0x045af028> (a java.lang.Object)
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
