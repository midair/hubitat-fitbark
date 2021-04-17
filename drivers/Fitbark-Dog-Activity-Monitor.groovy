/**
 *  Unofficial Hubitat Driver for FitBark Dog Activity Monitors
 *
 *  Copyright 2021 Claire Treyz
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 *  Change Log:
 *  2021-04-16: Initial
 *
 */

import groovy.transform.Field

metadata {
  definition(
      name: FITBARK_DRIVER_NAME,
      namespace: FITBARK_GROOVY_NAMESPACE,
      description: "Driver Code for FitBark Dog Activity Monitors 🐶",
      author: "Claire Treyz",
      importUrl: "https://github.com/midair/hubitat-fitbark/blob/main/drivers/Fitbark-Dog-Activity-Monitor.groovy"
  ) {


    /** === CAPABILITIES === */

    /**
     * NOTE: Instead of steps, the Step Sensor capability is being used here to track 'BarkPoints' – FitBark's
     * proprietary points system for tracking physical activity – which is pretty much the same idea. The FitBark Dog
     * Acitivity Monitor has a daily BarkPoints goal (just like a steps goal) and tracks BarkPoints progress over the
     * course of the day (like the steps count).
     */
    capability "StepSensor"
    // ↳ Attributes: 'steps' (NUMBER, unit: BarkPoints), 'goal' (NUMBER, unit: BarkPoints)

    capability "Battery"
    // ↳ Attributes: 'battery' (NUMBER, unit: %)

    capability "Sensor"
    // ↳ Attributes: None.

    capability "Refresh"
    // ↳ Commands: 'refresh()'

    capability "Polling"
    // ↳ Commands: 'poll()'

    capability "Initialize"
    // ↳ Commands: 'initialize()'

    /** === CUSTOM ATTRIBUTES === */

    // ==== Device Properties ====

    attribute "lastDeviceSyncToFitBark", "date"
    // ↳ The last time FitBark synced with the device (unrelated to the last time Hubitat synced with FitBark).

    attribute "bluetoothID", "string"
    // ↳ The device"s Bluetooth ID (not sure what this actually represents).

    // ==== Dog Profile ====

    attribute "userRelationshipType", "string"
    // ↳ Indicates whether the user is the dog's owner ('OWNER') or just a follower ('FRIEND').

    attribute "dogName", "string"
    // ↳ Name of the dog in its FitBark profile.

    attribute "dogBirthday", "date"
    // ↳ Birthdate of the dog in its FitBark profile.

    // ==== BarkPoints Tracking (Extends the Step Sensor) ====

    attribute "percentageOfDailyGoalComplete", "number"
    // ↳ The percentage of the Daily Activity Goal that has been completed so far today.

    attribute "percentageOfDailyGoalCompletedYesterday", "number"
    // ↳ The percentage of the Daily Activity Goal that was completed yesterday.

    attribute "scheduledDailyGoalUpdates", "json_object"
    // ↳ JSON Object returned from the FitBark API containing an array of the scheduled changes to the Daily Goal for
    // this device (or simply the current goal, if no changes are scheduled).

    attribute "hourlyAverageActivityPoints", "number"
    // ↳ Seems to be the average number of BarkPoints accrued per hour on that given day (?).

    // ==== Activity Level Time Breakdown ====

    attribute "minutesTodayPlayTime", "number"
    // ↳ The number of minutes of 'play' time that the FitBark device has registered so far today.

    attribute "minutesTodayActiveTime", "number"
    // ↳ The number of minutes of "active" time that the FitBark device has registered so far today.

    attribute "minutesTodayRestTime", "number"
    // ↳ The number of minutes of "rest" time that the FitBark device has registered so far today.

    // ==== Similar Dog Stats ====

    attribute "similarDogsAverageDailySteps", "number"
    // ↳ The daily average number of BarkPoints for dogs with similar breed/age characteristics.

    attribute "similarDogsAverageDailyRestMinutes", "number"
    // ↳ The daily average number of 'rest' time minutes for dogs with similar breed/age characteristics.

    /** === COMMANDS === */

    // TODO: Disable this command when |userRelationshipType| is not 'OWNER' (i.e. no 'Edit' access to the device).
    command(
        "scheduleDailyActivityGoalUpdate",
        [
            [
                name: "New Daily BarkPoints Goal [Must Be Dog's Owner]",
                type: "NUMBER",
                description: "Number of 'BarkPoints' you would like your dog to accrue each day. Must be greater than zero."
            ],
            [
                name: "New Goal Start Date",
                type: "DATE",
                description: "Date when you'd like the new goal to start. Must be in the future."
            ]
        ]
    )
  }

  preferences {
    input(
        name: "minimumLogOutputLevel",
        type: "enum",
        title: "Minimum Device Log Level to Output",
        options: [
            "${LOG_LEVEL_ERROR + 1} – NO LOGS",
            "${LOG_LEVEL_ERROR} – ERROR",
            "${LOG_LEVEL_WARN} – WARN",
            "${LOG_LEVEL_INFO} – INFO",
            "${LOG_LEVEL_DEBUG} – DEBUG",
            "${LOG_LEVEL_TRACE} – TRACE (ALL LOGS)"
        ],
        defaultValue: "${LOG_LEVEL_INFO} – INFO",
        required: true,
        submitOnChange: true
    )
    input(
        name: "fitBarkDevicePollingInterval",
        type: "enum",
        title: "Desired Interval Between Each FitBark Refresh Request",
        options: [
            "Every Minute [Not Recommended]",
            "Every Five Minutes",
            "Every Ten Minutes",
            "Every Fifteen Minutes",
            "Every Thirty Minutes",
            "Every Hour",
            "Every Three Hours",
            "Never (Disable Device Updates)"
        ],
        defaultValue: "Every Thirty Minutes",
        required: true,
        submitOnChange: true
    )
  }
}

/** ------------------------------ **/
/** -------- DEVICE STATE -------- **/
/** ------------------------------ **/

/**
 * Method called when the device is first created.
 */
void installed() {
  log.info("installed()")

  initialize()
}

/**
 * Method called when the preferences of a device are updated.
 */
void updated() {
  logTrace("updated()")

  initialize()
}

/** -------------------------- **/
/** -------- COMMANDS -------- **/
/** -------------------------- **/

/**
 * Initializes the device configuration.
 */
void initialize() {
  logTrace("initializeFitBarkDog()")

  // Unschedule before scheduling, just to be safe.
  logDebug("Unscheduling any polling/refreshing for a clean slate.")
  unschedule(poll)
  unschedule(refreshDailyGoalUpdates)
  unschedule(refreshSimilarDogStats)

  // Configure device refresh polling.
  String selectedPollingInterval = settings?.fitBarkDevicePollingInterval
  logInfo("Scheduling polling to run ${selectedPollingInterval}.")

  switch (selectedPollingInterval) {

    case "Every Minute [Not Recommended]":
      runEvery1Minute(poll)
      break

    case "Every Five Minutes":
      runEvery5Minutes(poll)
      break

    case "Every Ten Minutes":
      runEvery10Minutes(poll)
      break

    case "Every Fifteen Minutes":
      runEvery15Minutes(poll)
      break

    case "Every Thirty Minutes":
      runEvery30Minutes(poll)
      break

    case "Every Hour":
      runEvery1Hour(poll)
      break

    case "Every Three Hours":
      runEvery3Hours(poll)
      break

    case "Never (Disable Device Updates)":
      // Already unscheduled polling above.
      break

    default:
      logError("Unknown fitBarkDevicePollingInterval value: ${selectedPollingInterval}")
      break
  }

  logInfo("Scheduling the once-daily refresh jobs.")
  // Schedule the 'Daily Goal Updates' and 'Similar Dog Stats' to be updated just once daily.
  schedule(new Date(), refreshDailyGoalUpdates)
  schedule(new Date(), refreshSimilarDogStats)
}

/**
 * Manually triggers a request to poll the FitBark API and refresh the device properties.
 */
void refresh() {
  logTrace("refresh()")

  parent.handleDeviceRefresh(device)

  logInfo("Manually refreshing Daily Goal Updates and Similar Dog Stats.")
  refreshDailyGoalUpdates()
  refreshSimilarDogStats()
}

/**
 * Updates the stored 'Scheduled Daily Goal Updates' with the latest values.
 *
 * Scheduled to run once a day. Can be triggered manually as part of the |refresh| command.
 */
void refreshDailyGoalUpdates() {
  logTrace("refreshDailyGoalUpdates()")

  parent.handleGetDailyGoalUpdates(device)
}

/**
 * Updates the stored 'Similar Dog Stats' with the latest values.
 *
 * Scheduled to run once a day. Can be triggered manually as part of the |refresh| command.
 */
void refreshSimilarDogStats() {
  logTrace("refreshSimilarDogStats()")

  parent.handleGetSimilarDogStats(device)
}

/**
 * Automatically polls the FitBark API to refresh the device properties on a scheduled interval.
 */
void poll() {
  logTrace("poll()")

  parent.handleDeviceRefresh(device)
}

/**
 * Schedules an update to the dog's Daily Activity Goal.
 *
 * @param newBarkPointsGoal The input number to change the daily goal to.
 * @param newGoalStartDate The requested date when the new goal should take effect.
 */
void scheduleDailyActivityGoalUpdate(BigDecimal newBarkPointsGoal, Date newGoalStartDate) {
  logTrace("updateDailyGoal(newBarkPointsGoal: ${newBarkPointsGoal}, newGoalStartDate: ${newGoalStartDate})")

  if (device.currentValue("userRelationshipType") != "OWNER") {
    logError("Must be the dog's owner to update its Daily Goal (Relationship Type: '${userRelationshipType}'.")
    return
  }

  Integer newDailyGoal = newBarkPointsGoal.intValue()
  if (newDailyGoal <= 0) {
    logError("The new Daily Goal passed to updateDailyGoal must be positive (got: ${newDailyGoal}).")
    return
  }

  Date currentDate = new Date()
  if ((newGoalStartDate - currentDate) <= 0) {
    logError("The goal start date passed to updateDailyGoal must be in the future (got: ${newGoalStartDate}).")
    return
  }

  logDebug("Scheduling the Daily Goal to update to ${newDailyGoal} on ${currentDate}.")
  parent.handleUpdateDailyGoal(device, newDailyGoal, newGoalStartDate)
}

/** ------------------------- **/
/** -------- LOGGING -------- **/
/** ------------------------- **/

/**
 * Logs a message at the ERROR level, if the minimum log level is TRACE, DEBUG, INFO, WARN, or ERROR.
 *
 * @param msg The message to log.
 */
private void logError(String msg) {
  if (shouldOutputLogForLevel(LOG_LEVEL_ERROR)) {
    log.error msg
  }
}

/**
 * Logs a message at the WARNING level, if the minimum log level is TRACE, DEBUG, INFO, or WARN.
 *
 * @param msg The message to log.
 */
private void logWarn(String msg) {
  if (shouldOutputLogForLevel(LOG_LEVEL_WARN)) {
    log.warn msg
  }
}

/**
 * Logs a message at the INFO level, if the minimum log level is TRACE, DEBUG, or INFO.
 *
 * @param msg The message to log.
 */
private void logInfo(String msg) {
  if (shouldOutputLogForLevel(LOG_LEVEL_INFO)) {
    log.info msg
  }
}

/**
 * Logs a message at the DEBUG level, if the minimum log level is TRACE or DEBUG.
 *
 * @param msg The message to log.
 */
private void logDebug(String msg) {
  if (shouldOutputLogForLevel(LOG_LEVEL_DEBUG)) {
    log.debug msg
  }
}

/**
 * Logs a message at the TRACE level, if the minimum log level is TRACE.
 *
 * @param msg The message to log.
 */
private void logTrace(String msg) {
  if (shouldOutputLogForLevel(LOG_LEVEL_TRACE)) {
    log.trace msg
  }
}

/**
 * Compares the provided log level to the level configured in settings.
 *
 * @return Whether the given level is permitted by the configured minimum level of device logs to output.
 */
private boolean shouldOutputLogForLevel(Integer logLevel) {
  if (!settings?.minimumLogOutputLevel) {
    return logLevel >= LOG_LEVEL_INFO  // Fall back to a minimum level of INFO.
  }

  Integer minimumLogLevel = settings.minimumLogOutputLevel.split(" ")[0].toInteger()
  boolean should = minimumLogLevel <= logLevel
  return should
}

/** --------------------------- **/
/** -------- CONSTANTS -------- **/
/** --------------------------- **/

/** Name of this device driver – must align with the value used by the FitBark Hubitat App. */
@Field static String FITBARK_DRIVER_NAME = "FitBark Dog Activity Monitor"

/** Namespace of this device driver – must align with the value used by the FitBark Hubitat App. */
@Field static String FITBARK_GROOVY_NAMESPACE = "midair.fitbark"

/** Integer representation of the ERROR log level (the highest level, meaning only ERROR logs will be output). */
@Field static Integer LOG_LEVEL_ERROR = 4

/** Integer representation of the WARN log level (at this level, the levels WARN/ERROR will be output). */
@Field static Integer LOG_LEVEL_WARN = 3

/** Integer representation of the INFO log level (at this level, the levels INFO/WARN/ERROR will be output). */
@Field static Integer LOG_LEVEL_INFO = 2

/** Integer representation of the DEBUG log level (at this level, the levels DEBUG/INFO/WARN/ERROR will be output). */
@Field static Integer LOG_LEVEL_DEBUG = 1

/** Integer representation of the TRACE log level (the lowest level, meaning ALL log levels will be output). */
@Field static Integer LOG_LEVEL_TRACE = 0
