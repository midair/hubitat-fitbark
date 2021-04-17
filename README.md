# FitBark for Hubitat 🐾
    Incorporate your dog's activity data into your smart home hub.
---
An **unofficial** [FitBark Dog Activity Monitor](https://www.fitbark.com/) integration for
[Hubitat](https://hubitat.com/) home automation hubs.
---
## Table of Contents

  1. [Overview](#overview)
  2. [Prereq - FitBark API Setup](#prereq--fitbark-api-setup-a-nameprerequisitea)
  3. [Installation Instructions](#installation-instructions)
      1. [FitBark Device Driver Installation](#1-fitbark-device-driver-installation)
      2. [FitBark App Installation](#2-fitbark-app-installation)
      3. [FitBark Account Authorization](#3-fitbark-account-authorization)
      4. [FitBark Device Discovery](#4-fitbark-device-discovery)
  4. [Device Features](#device-features)
      1. [Polling Interval](#polling-interval)
      2. [Battery Capability](#battery-capability)
      3. [Step Sensor Capability](#step-sensor-capability)
  5. [Feature Requests](#feature-requests)
  6. [License](#license)
  
 ---
 
## Overview
 
_📌 Please note –– **devices will only have profile info and activity data.** The public
[FitBark API](https://www.fitbark.com/dev) currently does not provide access to location data or
any of their computed health metrics._

`FitBark for Hubitat` includes a custom
[app](https://github.com/midair/hubitat-fitbark/apps/Fitbark.groovy) and a custom
[driver](https://github.com/midair/hubitat-fitbark/drivers/Fitbark-Dog-Activity-Monitor.groovy).
Once installed, you'll be able to authorize FitBark account access and discover devices.

Linked devices will be updated by polling the FitBark API on a customizable polling interval.

#### ***💡 Potential Automation Ideas***

* Send a notification to your phone when your dog has not reached at least 75% of their daily
 activity goal by sunset.
 
* Schedule changes to increase or decrease your dog's daily activity goal.

* Play soothing music at home if your dog's activity spikes while you are away.

* Add battery charging reminders whenever you arrive at home with the battery under 20%.

---

## Prereq – FitBark API Setup

Before you can set up the Hubitat app, you'll need to fill out a 
[FitBark Developer Application](https://www.fitbark.com/dev/) form to request FitBark API
Credentials and wait for your application to be approved.

_You can leave the `Redirect URL` and `Service URL` fields blank – the app will register the Hubitat
Redirect URL for you._ 

Once you're approved, you'll get an email with your `FitBark Client ID` and `FitBark Client Secret`.

---

 ## Installation Instructions
 
 #### 1. FitBark Device Driver Installation
 
 1. Open the Hubitat `Drivers Code` page for your hub.
 2. Press the `New Driver` button in the upper right-hand corner.
 3. Paste the code from
 [`Fitbark-Dog-Activity-Monitor.groovy`](https://github.com/midair/hubitat-fitbark/blob/main/drivers/Fitbark-Dog-Activity-Monitor.groovy)
 and save.
 
 --- 
 
 #### 2. FitBark App Installation
 
 1. Open the Hubitat `Apps Code` page for your hub.
 2. Press the `New App` button in the upper right-hand corner.
 3. Paste the code from
 [`Fitbark.groovy`](https://github.com/midair/hubitat-fitbark/blob/main/apps/Fitbark.groovy)
 and save.
 4. After saving, an `OAuth` button should appear in the upper right-hand corner – click on it.
 5. Click on `Enable OAuth in App` and then click `Update` (just ignore the auto-filled `Client ID`
  and `Client Secret` fields).
 6. Open the Hubitat `Apps` page for your hub.
 7. Press the `Add User App` button in the upper-right hand corner and select the newly added
  `FitBark` app.
 8. Click the `Done` button on the FitBark App installation page to complete installation.
 
     ![FitBark Hubitat App Installation](https://user-images.githubusercontent.com/5731973/115119046-b0b28400-9f63-11eb-9631-e9c97901b88f.png)
 
 --- 
     
 #### 3. FitBark Account Authorization
 
 1. Open the Hubitat `Apps` page for your hub.
 2. Find and click on the newly added `FitBark` app.
 3. Input the FitBark API `Client ID` and `Client Secret` that were emailed to you (as mentioned
  [above](#prerequisite--fitbark-api-setup))
 , then click
  `Next`.
 
     ![FitBark Hubitat Setup Step #1](https://user-images.githubusercontent.com/5731973/115102130-4b7c7580-9f06-11eb-9549-b06526f36a01.png)
 
 4. Click on the `Validate FitBark Redirect URIs` to add the Hubitat Redirect URI to your FitBark
  API configuration.
 
     ![FitBark Hubitat Setup Step #2](https://user-images.githubusercontent.com/5731973/115097463-96d35b80-9ee7-11eb-9428-c6b332acaa39.png)

5. Click on the `Authorize FitBark` button, sign in to your FitBark account, and authorize the
 application.
     ![FitBark Hubitat Setup Step #3](https://user-images.githubusercontent.com/5731973/115097465-976bf200-9ee7-11eb-9528-3431a9c10e04.png)

6. You should be redirected back to the FitBark Hubitat app with your account connected.
 
 --- 
    
 #### 4. FitBark Device Discovery

1. Once you've authorized your account in the FitBark Hubitat app, click on the `Connect Your
 FitBark Devices` button
to link the FitBark devices from your account.

     ![FitBark Hubitat – Connect Devices](https://user-images.githubusercontent.com/5731973/115097460-95099800-9ee7-11eb-95b9-539f9405f17d.png)

2. This should open a Device Discovery page, which will auto-refresh once discovery is complete.

     ![FitBark Hubitat – Device Discovery Complete](https://user-images.githubusercontent.com/5731973/115102039-d14bf100-9f05-11eb-92db-70f079fab680.png)
 
---

## Device Features

### Polling Interval

Linked FitBark device attributes are updated by
[polling](https://en.wikipedia.org/wiki/Polling_(computer_science)) the FitBark API at a regular
interval to check whether any values have changed. The state returned from the FitBark API will
reflect the most recent data synced from the device.

The default polling interval is 30 minutes. You can configure the frequency of the polling interval
in the device preferences. The longer polling interval that works for you, the better – you may
start to run in to unknown issues if you increase the frequency.

![FitBark Hubitat Polling Interval](https://user-images.githubusercontent.com/5731973/115097893-08140e00-9eea-11eb-9a11-a4d3425361fa.png)

According to [this answer](https://www.fitbark.com/articles/how-often-does-the-fitbark-gps-sync/), 
FitBark GPS devices try to sync once per minute when connected to your phone over BLE – versus once
per hour when only a Wi-Fi connection is available.

> ➟ If you don't like the idea of having any polling, no matter the interval, you have the option
> to set the device polling interval preference to `Never (Disable Automatic Device Updates)`.
>
> The device attributes would no longer be useful automation *triggers*, but you could still trigger
> the `refresh` command from other automation routines to access up-to-date device data.

### Battery Capability

The [Battery Capability](https://docs.hubitat.com/index.php?title=Driver_Capability_List#Battery)
 represents the battery level of the physical FitBark device _(as one might expect)_.

###### Attributes

`battery` – The most recent battery percentage reported by the FitBark device.
 
### Step Sensor Capability

The
 [Step Sensor Capability](https://docs.hubitat.com/index.php?title=Driver_Capability_List#StepSensor)
 monitors the dog's activity throughout the day. Although _technically_ FitBark devices are not
 counting "steps", they represent the same concept as a step counter. The "steps" being counted in
 this case are [BarkPoints](https://www.fitbark.com/articles/what-are-barkpoints/) – FitBark's
 proprietary point system for physical activity.
 
###### Attributes

`steps` – The current number of [BarkPoints](https://www.fitbark.com/articles/what-are-barkpoints/) 
 that the dog has accumulated since the start of the day.

`goal` – The goal number of [BarkPoints](https://www.fitbark.com/articles/what-are-barkpoints/) 
 that the dog will ideally achieve by the end of the day.

---

## Feature Requests
 
Any suggestions, questions, issues, or improvements are welcome!

You may also want to check out the
[FitBark API documentation](https://www.fitbark.com/dev/) in case you'd like to request one of the
attributes that wasn't included.

---

## License

[Apache 2.0 License](https://github.com/midair/hubitat-fitbark/blob/main/LICENSE) – © Claire Treyz