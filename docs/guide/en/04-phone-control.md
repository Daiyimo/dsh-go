# Phone control

Let DSH read the screen, tap and type to get things done on your phone. Pause or stop it anytime. Phone control works only with this phone's engine and isn't loaded in Safe mode.

## Turn it on

<!-- nav: commonSettings,hubSettingsPhoneControl -->
`Settings › Phone control` · [Open](dshgo://open/phone)

1. Turn on the switch at the top. The first time, you'll see what's read and where it goes; tap **Agree and enable**. Turning it on or off restarts a running engine, interrupting any reply in progress.
2. Under **Setup**, tap **Turn on** next to **Accessibility service**, and turn on "DSH Phone Control" in Android's accessibility settings. If the switch won't turn on for a sideloaded app, tap **Accessibility switch disabled?** for brand-specific steps to allow restricted settings.
3. Tap **Turn on** next to **Notifications** and allow notifications in App info. Approvals and the stop button need them.
4. Tap **Read current screen** to see what DSH can read. This reading isn't sent to the model.

During a task, text on the screen is sent to the model you chose; with a vision model you can ask for a screenshot. Games, self-drawn canvas pages and encrypted or obfuscated pages have no usable controls, so DSH attaches a screenshot automatically — see "Games and self-drawn pages" below. Some recognized card and ID numbers are masked where possible, but not all sensitive information can be guaranteed to be detected or hidden.

## Restore accessibility automatically

<!-- nav: commonSettings,hubSettingsPhoneControl,a11yRestoreTitle -->
`Settings › Phone control › Restore accessibility automatically`

Open this row under **Setup**, read the explanation and tap **Authorize and enable**. A connected Shizuku or self-ADB channel can grant permission. If neither is available, follow the setup page or copy the displayed command and run it once on your computer. Restoration requires both the in-app phone-control switch and the system permission.

After granting permission, turning off the accessibility service in Android settings will trigger an attempt to turn it back on. To stop phone control, turn off the switch inside DSH Go. Restoration changes only DSH Go's service entry and preserves other accessibility services.

After a system force-stop, reopen DSH Go before it can recover. Repeated failures back off to about ten minutes between attempts. This permission does not keep the app alive indefinitely or fix the underlying cause of a service crash.

## Ask DSH to do something

Go back to a chat and say what you want done. While a task runs:

- Keep the screen on and unlocked. Turning the screen off stops the task.
- Passwords, verification codes, payments and system settings are up to you (unless you turn them on under Lift limits below). When a step needs you, **Your turn** appears; tap **Continue** when you're done. When one of these rules is the reason, the card's title says so, like **Passwords and codes are yours to enter** or **System settings are yours to change**.
- A task ends on its own when DSH makes no new move for 3 minutes.

Alarms, events, maps and sharing don't need the accessibility service.

## Games and self-drawn pages

Games, self-drawn canvas pages and encrypted or obfuscated pages have no readable controls, so DSH can't find elements to tap. On these pages DSH:

- Attaches a screenshot automatically: the current picture is sent straight to a vision model, with a 10x10 grid drawn on it, each cell standing for 100, so positions can be described.
- Taps by grid: the model reports 0-1000 grid coordinates, (0,0) is the top-left of the screenshot and (1000,1000) the bottom-right; DSH converts them to screen coordinates, whatever the phone's resolution.
- Checks the tap: after tapping, DSH compares the picture before and after. When nothing changed it sends the model a checklist — coordinates measured in screenshot pixels instead of grid units, the status bar in the way, the keyboard covering the target — and after two misses in a row it suggests stopping and handing the screen back to you instead of tapping the same spot again.
- Approvals: coordinate taps on these pages still ask first by default; with **This task**, and once this app has been approved in the current task, they stop asking. **Always ask** keeps asking every time.

A model that can't see pictures can't help on these pages: there is no tree to read and the screenshot means nothing to it, so DSH refuses coordinate taps and makes the model hand the phone back to you instead of guessing coordinates until the budget runs out.

When the whole-screen picture is too small to read the fine print, the model can ask for a region on screen (for example 200,300,800,900, 0-1000) and DSH zooms into just that part; coordinates on the zoomed picture still count from its own top-left as 0,0. While a page is still loading or an animation is still running, the model can also wait a moment before reading the screen again.

These pages and WeChat are worked pure GUI, one fullscreen app for the whole task:

- The virtual screen is taken down: such a task has no use for a second screen. When the page is recognised, DSH closes it and the app works on your screen.
- One fullscreen app only: asking for the back screen later in the task is refused, and the reply says the task works from the picture.
- No floating bar: anything drawn on the picture moves every position measured against it, and on WeChat an outside setup reading the screen gets pushed around. To stop the task, use the stop on the **Operating your phone** card in the app.
- Landscape works too: an app that declares landscape is no longer launched inside a portrait-shaped window (that letterboxes it and shifts every coordinate). Turning the screen voids an older screenshot: tapping on it is refused with a request to screen again, and a frame caught mid-turn says the picture and the screen disagree — wait for it to settle and read again.
- Home is not another app: pressing home mid-task and opening WeChat from the launcher keeps the same task, so the bar does not come back. Anything drawn before that is taken down as WeChat comes to the front. The other way round, inside a pure-GUI task DSH will not press home or tap and swipe on the home screen by itself: this ROM re-opens the app into its small window there, and a sideways swipe on the home screen lands on 负一屏, after which the task never gets its app back — so when the phone sits on the home screen DSH first brings the task's app back to the front and then goes on instead of tapping an icon; only when that fails is the step refused, with a note to screen the current page and use open to change apps.

WeChat is on that list out of the box (**Pure GUI apps**, under **App rules** on the phone control page): add one by package name, removing one asks once; an empty list means every app is treated like any other. Games and canvas pages don't need adding — one read that turns out to be all picture switches the task to pure GUI.

## Floating bar

When a task starts, a floating bar appears at the top of the screen showing what DSH is doing, with **Pause**, **Stop** and **Collapse**:

- After **Pause**, the button reads **Continue**; tap it to let DSH carry on.
- Tap the text on the bar to steer: tell DSH to try another way.
- **Collapse** shrinks the bar to a ball at the screen edge; tap the ball to open it. Drag the ball anywhere; it snaps to the nearest edge and stays there next time.
- After 3 seconds untouched, the bar fades; touch it to bring it back. It stays clear whenever you need to approve something.

Adjust it under **Floating bar** on the phone control page (shown once phone control is on):

- **Idle opacity**: 30% to 100%, 70% by default. At 100% the bar never fades.
- **Collapse when idle**: off by default. When on, the bar shrinks to a ball after a few idle seconds, and opens by itself when you need to approve something.
- **Back to DSH Go when done**: on by default. When the reply ends and the app is in the background, it tries to bring you back to that chat. If the system blocks background windows, use the notification; see Confirmations and return below.

Whatever the settings, the bar closes when the reply ends.
When the foreground app is WeChat, or while a game or another picture-only page is being worked, DSH does not put the bar on the screen at all: some external setups read the screen and pick their own tap positions, and a bar on top of it gets in their way — on a game it also shifts every position measured against the screenshot. Stop the task from the **Operating your phone** card in the app instead.

## Stop

- **Stop** on the floating bar.
- **Stop** on the "Operating your phone" card on the phone control page.
- **Stop actions** on the engine notification. **Stop** on the same notification stops the whole engine.

Stopping phone control also stops every reply in progress on this phone's engine.

## Confirmation

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneConfirmTitle -->
`Settings › Phone control › Confirmation`

- **Always ask** (default): asks before entering a new app, sending, deleting or paying.
- **This task**: asks once per task. Choose **Allow for task** on the approval card, and the rest of the task goes ahead.
- **Always skip**: acts without asking. Asks you to confirm before turning on.

When you pick Phone control during first-time setup, you choose once after agreeing to the disclosure: **Ask me at key steps** (Always ask) or **Fully automatic** (Always skip). You can change it here anytime.

An approval card left for 30 seconds counts as **Decline**. In every mode, system settings and permission pages, passwords and verification codes, and DSH Go itself stay with you unless turned on under **Lift limits**; apps set to Hands off are changed in **App rules**. Confirmation lists these, with links to both.

## Confirmations and return

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneBackgroundGuideTitle -->
`Settings › Phone control › Confirmations and return`

Once phone control is on, this row is under **Advanced** on the phone control page; tap it to read the guidance again. **Open system settings** opens DSH Go's system App info page.

When a step needs your decision, DSH Go shows a card. If the card is unavailable, it tries a notification or returns to the app. Allow phone-control notifications and pop-up alerts in system settings. Some phones also offer a permission to display pop-up windows while running in the background; allowing it can help the app return. Menu names vary by system version.

If the app doesn't return after a task finishes, tap **Task complete · View result** in the notification. If the system blocks notifications or background windows, open DSH Go yourself to see the result. Turning on **Back to DSH Go when done** doesn't guarantee that the system will allow it.

## App rules

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneAppRulesTitle -->
`Settings › Phone control › App rules`

Set each app to **Hands off**, **Every step** or **As usual**. Payment, banking and brokerage apps are **Hands off** by default, and WeChat is **Every step**. Unlisted apps work as usual, and sensitive actions still need approval. Loosening a rule explains the risk first; tap **Change anyway** to apply it. Tap **Add app** to add one by package name.

## Lift limits

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneUnlockTitle -->
`Settings › Phone control › Lift limits` · [Open](dshgo://open/phone-unlocks)

All three are off by default, and each turns on separately:

- **System settings**: permissions, installers, security center, notifications.
- **Passwords and codes**: fill in passwords, verification and payment codes. The action log shows •••• and never what was typed.
- **DSH Go itself**: tap and type inside DSH Go.

Turning one on opens a page first: what AI can then do, what could go wrong, and the disclaimer below. Press and hold **I understand the risks, turn on** for about 1.5 seconds to apply it. Turning one off takes a single tap. Nothing can be turned on while a task is running. While any is on, the Phone control page and the floating bar show **Limits lifted**; tap it to come back here. Banking and payment apps aren't here; change them in **App rules**.

Disclaimer:

- After lifting a restriction, AI may perform permitted high-risk actions without step-by-step confirmation.
- AI may misread a screen or follow malicious instructions in pages, messages, or files.
- Your accounts, money, data, device, or privacy may be harmed. Review consequential actions and results.
- The software is provided as is. Liability limits apply only where permitted by law; mandatory rights remain.
- Enable temporarily only on an owned or authorized device. Do not test with real payment details on your primary phone.

Read the [full risk notice and disclaimer](https://github.com/Daiyimo/dsh-go/blob/main/DISCLAIMER.en.md) for more on authorization, data transmission, ADB/Shizuku and statutory rights.

## Action log and advanced

**Recent actions** logs every step, without screen contents. Tap **All actions** to see them. Each entry also records how much the screen changed afterwards (0 to 1: the element distance on a tree page, the pixel difference on a screenshot page), so it is visible afterwards which steps actually did something. The log lasts until the app closes.

Under **Advanced**:

- **Virtual screen**: the quick on/off for that mode, off by default. On it, the agent reads and taps on a trusted virtual screen; turning it off also ends the running task and drops the Shizuku / ADB link, so nothing keeps working behind your back. Pure-GUI tasks (WeChat, games) never use it: they do not open the virtual screen, and one left on is closed for them.
- **Open apps fullscreen**: on by default; when an app is opened the accessibility service asks for a fullscreen window, so it does not run in a small or floating one. Off leaves the start to the system. Some ROMs (StepOS among them) answer a launch made from an app with their own small window whatever the launch asks for; after opening, DSH puts the task back into fullscreen through the privileged channel (Do not disturb or self-ADB) and then looks at which window the task really got: still a small one and it asks again, up to three times, so there is no need to go to the home screen and tap the icon. On a device where it changes nothing (StepX-like ones that only go fullscreen on the virtual screen), turn it off and keep using **Non-disruptive mode** below; both ways stay, and a pure-GUI app like WeChat gets no bar either way.
- **DSH Keyboard**: a system input method used as a fallback, off by default. A picture page like WeChat or a game has no field ids, so DSH types into the app through the privileged channel first (Do not disturb or self-ADB): ASCII goes through `input text` and the keyboard takes no part; Chinese cannot go that way, so: a page whose field can be read gets the text on the clipboard and pasted straight into the cursor; a page that hides its fields (WeChat, games) has the current keyboard switched to DSH's own for one commit and switched straight back to yours — without you doing anything. WeChat ignores the paste key (measured), which is why it goes through the input method; a tap that opens a search box has already brought the keyboard up with the cursor in the field, so just type — tapping the field again dismisses the keyboard and loses the paste. only when neither works are you told to long press the field and pick 「Paste」. It draws no keyboard of its own, only a strip at the bottom saying DSH is typing, with 「Use my keyboard」 next to it. Only when neither privileged path is set up do you have to switch it on in the system's keyboard list and make it the keyboard in use; both actions are on this row. A page whose fields can be read does not need it.
- **The model can read screenshots**: on by default. A pure-GUI page is only a picture, with no controls to read; turn this off when your model cannot read images, and DSH hands the phone back to you instead of guessing at coordinates, and says why when it reads such a page. The app also decides per model, and its guess never overwrites a value you set yourself.
- **Non-disruptive mode**: works on a hidden screen while you use your phone. Needs Android 14 or later; choose **Use Shizuku** or **Direct ADB** and follow the setup. It needs Wi-Fi with Developer options and wireless debugging on, must be reactivated after restarting your phone, and the screen must still stay on and unlocked.
- **Background running**: prevents Android from turning off accessibility; opens the phone settings guide.
- Background freeze: some systems freeze DSH Go three seconds after it goes to the background, and every call then waits half a minute to answer “timeout” (StepOS does). On the first tool call of a task DSH adds itself to the system battery whitelist through the privileged channel (`cmd deviceidle whitelist`, the log says `freeze guard: whitelist ok=true`); systems that ignore that list get a broadcast of DSH's own every two seconds while tools are being called, which keeps the freeze pending instead of landing (the log says `keep awake: starting`). Setting DSH Go to “unrestricted” in the phone battery settings does the same. A real freeze shows up in the log as `frozen 53700ms`, and that frozen time is not charged to the call, so the call finishes after the thaw instead of answering “timeout”.
- Troubleshooting: DSH writes what it decided at every step to the log; with the phone on a cable, `adb logcat -s DshPhone` shows which app was opened, why the virtual screen was not used, which way the text went, and where Shizuku or self-ADB stopped. The same lines are also kept in the app's own directory, so they survive logcat rolling over: `adb pull /sdcard/Android/data/io.github.ccch1mneyyy.dsh_app/files/dsh-phone.log`.
- **Confirmations and return**: see confirmations and task results in time; see Confirmations and return above.
