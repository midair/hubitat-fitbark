/**
 *  Unofficial FitBark Hubitat App
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

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.time.TimeCategory
import groovy.transform.Field
import groovyx.net.http.ContentType
import java.text.SimpleDateFormat

definition(
    name: "FitBark 🐾",
    namespace: FITBARK_GROOVY_NAMESPACE,
    author: "Claire Treyz",
    description: "Unofficial Hubitat Integration for FitBark",
    category: "Pets",
    oauth: true,
    importUrl: "https://github.com/midair/hubitat-fitbark/blob/main/apps/Fitbark.groovy",
    documentationLink: "https://github.com/midair/hubitat-fitbark/blob/main/README.md",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
)

preferences {
  page(name: "mainPage")
  page(name: "deviceDiscoveryPage")
  page(name: "debugPage")
}

mappings {
  path("/authentication") {
    action: [
        GET: "handleFitBarkAuthenticationRedirect"
    ]
  }
}

/** ------------------------------------ **/
/** -------- PAGE CONFIGURATION -------- **/
/** ------------------------------------ **/

/**
 * Creates the Main Page configuration for the FitBark Hubitat App.
 *
 * @return A map defining the Main Page.
 */
Map mainPage() {
  logTrace("mainPage()")
  logDebug("######## Loading the App Main Page. ########")

  if (!state.isAppInstalled) {
    log.debug("Showing the app installation page.")
    return dynamicPage(name: "mainPage", title: "<i>FitBark App Installation</i>", install: true) {
      createHeaderSection("FitBark App Installation")
      section {
        paragraph "Click the 'Done' button below to finish installing the app – then come back to complete setup."
      }
    }
  }

  if (isMissingUserInputFitBarkClientCredentials()) {
    return dynamicPage(name: "mainPage", title: "<i>FitBark App Setup – Step 1 of 3</i>", uninstall: true, nextPage: "mainPage") {
      createHeaderSection("FitBark API Credentials")
      createFitBarkClientCredentialInputSection()   // App Setup Step #1.
    }
  }

  if (!hasFitBarkAccessToken()) {
    String stepNum = state.hasValidHubitatRedirectURI ? "3" : "2"
    return dynamicPage(name: "mainPage", title: "<i>FitBark App Setup – Step ${stepNum} of 3</i>", uninstall: true) {
      createHeaderSection(state.hasValidHubitatRedirectURI ? "FitBark App Authorization" : "FitBark API Validation")
      createFitBarkClientCredentialDisplaySection()

      createDividerSection()
      createFitBarkRedirectURIVerificationSection()  // App Setup Step #2.
      createFitBarkAuthorizationLinkSection()        // App Setup Step #3.

      createDividerSection()
      createFitBarkAuthHelpSection()
      createDividerSection()
    }
  }

  return dynamicPage(name: "mainPage", uninstall: true) {
    createHeaderSection("FitBark Account")
    createFitBarkAuthStatusSection()

    createHeaderSection("FitBark Devices 🐶")
    if (hasLinkedFitBarkDogMonitors()) {
      createLinkedDevicesDescriptionSection()
      createCurrentlyLinkedFitBarkDogMonitorsSection()
      createDividerSection()
    }
    createDiscoverLinkedFitBarkDogMonitorsSection()

    createHeaderSection("Settings")
    createDebugPageLinkSection()

    createDividerSection()
  }
}

/** --------------------------- **/
/** -------- APP STATE -------- **/
/** --------------------------- **/

/**
 * Callback method called when the FitBark Hubitat App is first installed.
 */
void installed() {
  // If the user doesn't click 'Done' before they leave the page, the page does not get installed and is stuck in limbo.
  state.isAppInstalled = true

  // Make sure default logging settings have been enabled.
  app.updateSetting("logOutputEnableError", true)
  app.updateSetting("logOutputEnableWarningLevel", true)
  app.updateSetting("logOutputEnableInfo", true)

  logWarn("installed() – FitBark Hubitat App has been installed.")

  // Create and store an OAuth access token in `state.accessToken`.
  createAccessToken()

  // Re-initialize app after hub reboots.
  subscribe(location, "systemStart", initializeFitBarkApp)
}

/**
 * Callback method called when the preferences of the FitBark Hubitat App are updated.
 */
void updated() {
  logTrace("updated()")
  logInfo("FitBark Hubitat App updated.")

  // Re-initialize app after hub reboots.
  subscribe(location, "systemStart", initializeFitBarkApp)
}

/**
 * Cleanup method called when the FitBark Hubitat App is uninstalled.
 */
void uninstalled() {
  log.warn("uninstalled() – Uninstalling the FitBark Hubitat App.")

  // Sign out and delete any stored credentials – this will also delete any linked Child Devices.
  signOutFitBarkAuthorization()
  deleteFitBarkAPIClientCredentials()

  // Unsubscribe / unschedule from any and all event subscriptions / scheduled handlers.
  unsubscribe()
  unschedule()
}

/**
 * Initializes the app configuration at launch.
 */
void initializeFitBarkApp(evt) {
  logInfo("initializeFitBarkApp(${evt})")

  refreshAllLinkedFitBarkDogMonitors()
}

/** ------------------------------- **/
/** -------- AUTHORIZATION -------- **/
/** ------------------------------- **/

/**
 * FitBark OAuth Step #1: Input FitBark Client Credentials.
 *
 * Creates input fields for the user's Client ID and Client Secret for the FitBark API.
 *
 * @return A map defining a section with text input fields.
 */
Map createFitBarkClientCredentialInputSection() {
  logTrace("createFitBarkClientCredentialInputSection()")

  // Hide the FitBark API Client Credential Input Section once completed. Credentials can be reset in Debug Tools.
  if (!isMissingUserInputFitBarkClientCredentials()) {
    return [:]
  }

  StringBuilder instructionsHTMLStringBuilder = new StringBuilder()
  instructionsHTMLStringBuilder << "<p>To authorize your FitBark account, you'll first need to register as a FitBark Developer.</p>"
  instructionsHTMLStringBuilder << "<ol type='1'>"
  instructionsHTMLStringBuilder << "<li>Submit a FitBark Developer Application at <a href='https://www.fitbark.com/dev/'>https://www.fitbark.com/dev/</a>."
  instructionsHTMLStringBuilder << "<i>Note: Set the 'Redirect URI' field to '${HUBITAT_STATE_REDIRECT_URL}'.</i></li>"
  instructionsHTMLStringBuilder << "<li>Wait for FitBark to approve you (may take a few days) and send you an email with your FitBark API Client Credentials.</li>"
  instructionsHTMLStringBuilder << "<li>Copy the FitBark API Client ID and Client Secret values from that email into the text boxes below.</li>"
  instructionsHTMLStringBuilder << "<li>Click 'Next' to proceed with FitBark Authentication Setup.</li>"
  instructionsHTMLStringBuilder << "</ol>"
  String instructionsHTMLString = instructionsHTMLStringBuilder.toString()

  return section {
    paragraph instructionsHTMLString
    input name: "fitBarkClientID", type: "text", title: "FitBark API Client ID", description: "Your FitBark API Client ID", required: true, submitOnChange: false
    input name: "fitBarkClientSecret", type: "password", title: "FitBark API Client Secret", description: "Your FitBark API Client Secret", required: true, submitOnChange: false
  }
}

/**
 * Creates a section that presents the input values for the user's Client ID and Client Secret for the FitBark API.
 *
 * @return A map defining a section displaying FitBark API Client Credentials.
 */
Map createFitBarkClientCredentialDisplaySection() {
  logTrace("createFitBarkClientCredentialDisplaySection()")

  // Build an HTML string displaying the current stored FitBark Client ID and Client Secret.
  String censoredClientSecret = makePartiallyCensoredString(fitBarkClientSecret)
  StringBuilder currentClientIDHTMLStringBuilder = new StringBuilder()
  currentClientIDHTMLStringBuilder << "<ul>"
  currentClientIDHTMLStringBuilder << "<li><i>Fitbark Client ID: <font size='+1'><b>${fitBarkClientID}</b></font></i></li>"
  currentClientIDHTMLStringBuilder << "<li><i>Fitbark Client Secret: <font size='+1'><b>${censoredClientSecret}</b></font></i></li>"
  currentClientIDHTMLStringBuilder << "</ul>"
  String currentClientIDHTMLString = currentClientIDHTMLStringBuilder.toString()

  return section {
    paragraph currentClientIDHTMLString

    if (state.hasValidHubitatRedirectURI) {
      createDividerParagraph()
      paragraph "✅ FitBark API Client Redirect URI configuration has been validated."
    }
  }
}

/**
 * Creates a section with a button for the user to reset their Client ID and Client Secret for the FitBark API.
 *
 * @return A map defining a section displaying a FitBark API Client Credential Reset Button.
 */
Map createFitBarkClientCredentialResetSection() {
  logTrace("createFitBarkClientCredentialResetSection()")

  // Build an HTML string with a label for the "Reset Credentials" button.
  StringBuilder clickBelowHTMLStringBuilder = new StringBuilder()
  clickBelowHTMLStringBuilder << "<br><br><br><br>"
  clickBelowHTMLStringBuilder << "<font size='-1'>"
  clickBelowHTMLStringBuilder << "Click the button below to delete and re-enter your FitBark Client Credentials."
  clickBelowHTMLStringBuilder << "</font>"
  String clickBelowHTMLString = clickBelowHTMLStringBuilder.toString()

  return section {

    paragraph clickBelowHTMLString

    input(
        name: "deleteFitBarkAPIClientCredentials",
        type: "button",
        title: "Reset FitBark API Client Credentials",
        submitOnChange: true
    )

  }
}

/**
 * FitBark OAuth Step #2: FitBark Authentication Page.
 *
 * Creates the authentication link using the FitBark Client API credentials input by the user in the app preferences.
 *
 * @return A map defining a section with the link to FitBark sign-in.
 */
Map createFitBarkAuthorizationLinkSection() {
  logTrace("createFitBarkAuthorizationLinkSection()")

  // Don't show the authorization link if:
  // - The user has not yet input valid Client ID and Client Secret values.
  // - The user already authorized their account.
  // - The user has not yet validated their FitBark API Redirect URI configuration.
  if (isMissingUserInputFitBarkClientCredentials() || hasFitBarkAccessToken() || !state.hasValidHubitatRedirectURI) {
    return [:]
  }

  // 3. Display a link that will open a page to log-in to their FitBark account to grant App access.
  return section {
    paragraph "To complete setup and begin linking your FitBark devices, " +
        "you'll need to authorize the Hubitat App to access your FitBark account. " +
        "\n\n" +
        "Click the button below to sign in to your FitBark account."

    createLinkButton(createFitBarkAuthorizationURL(), "Authorize FitBark")
  }
}

/**
 * FitBark OAuth Step #3: Receiving a (Temporary) FitBark Authorization Code.
 *
 * Processes the redirect request with the Authorization Code from a successful FitBark authentication and starts the
 * request to exchange the temporary Authorization Code for a long-lived Access Token.
 *
 * @return An HTTP response with a 'Success' page (or 'Failure' page, if applicable).
 */
Map handleFitBarkAuthenticationRedirect() {
  logTrace("handleFitBarkAuthenticationRedirect()")

  // 1. Make sure the temporary authorization code was returned in the redirect parameters.
  String fitBarkAuthorizationCode = params.code
  if (!fitBarkAuthorizationCode) {
    logError("Missing expected FitBark Authorization Code in request params. Params: ${params}.")

    StringBuilder failureHTMLStringBuilder = new StringBuilder()
    failureHTMLStringBuilder << "<!DOCTYPE html><html>"
    failureHTMLStringBuilder << "<head><title>ERROR</title></head>"
    failureHTMLStringBuilder << "<body>Authentication Failure</body>"
    failureHTMLStringBuilder << "</html>"
    String failureHTMLString = failureHTMLStringBuilder.toString()

    return render(contentType: "text/html", status: 500, data: failureHTMLString)
  }

  logInfo("Successful FitBark authentication redirect (Temp Authorization Code: ${fitBarkAuthorizationCode}).")

  // 2. Get the user's long-term OAuth Access Token (expires after one year).
  boolean wasAccessTokenFetchSuccessful = getFitBarkAccessToken(fitBarkAuthorizationCode)
  if (!wasAccessTokenFetchSuccessful) {
    logError("Getting FitBark Access Token Failed.")

    StringBuilder failureHTMLStringBuilder = new StringBuilder()
    failureHTMLStringBuilder << "<!DOCTYPE html><html>"
    failureHTMLStringBuilder << "<head><title>ERROR</title></head>"
    failureHTMLStringBuilder << "<body>Access Token Fetch Failed</body>"
    failureHTMLStringBuilder << "</html>"
    String failureHTMLString = failureHTMLStringBuilder.toString()

    // Keep track of auth events in the App Event log.
    sendEvent(name: "FitBark Account Authorization", value: "Failure")

    return render(contentType: "text/html", status: 500, data: failureHTMLString)
  }
  logTrace("handleFitBarkAuthenticationRedirect(getFitBarkAccessToken -> wasAccessTokenFetchSuccessful=true)")

  // 3. Create a link back to the FitBark Hubitat App.
  String fitBarkAppMainPageLink = "http://${location.hub.localIP}/installedapp/configure/${app.id}/mainPage"

  // 4. Create a string containing the HTML to display a success page and a link back to the FitBark Hubitat App page.
  StringBuilder successHTMLStringBuilder = new StringBuilder()
  successHTMLStringBuilder << "<!DOCTYPE html><html>"
  successHTMLStringBuilder << "<head>"
  successHTMLStringBuilder << "<title>Hubitat Elevation - FitBark Integration</title>"
  successHTMLStringBuilder << "<meta http-equiv='refresh' content='3; URL =${fitBarkAppMainPageLink}'>"
  successHTMLStringBuilder << "</head>"
  successHTMLStringBuilder << "<body style='background-color:powderblue;font-family:verdana;text-align:center'>"
  successHTMLStringBuilder << "<h1>Hooray!</h1>"
  successHTMLStringBuilder << "<h3>You have successfully linked your FitBark account.</h3>"
  successHTMLStringBuilder << "<br><br><br>"
  successHTMLStringBuilder << "<p><i>This page will automatically redirect back to the FitBark Hubitat App after three seconds.</i></p>"
  successHTMLStringBuilder << "<p><a href=${fitBarkAppMainPageLink}>Return to the FitBark Hubitat App Immediately</a></p>"
  successHTMLStringBuilder << "</body>"
  successHTMLStringBuilder << "</html>"
  String successHTMLString = successHTMLStringBuilder.toString()

  sendEvent(name: "FitBark Account Authorization", value: "Success")

  // 5. Render the success page HTML.
  return render(contentType: "text/html", status: 200, data: successHTMLString)
}

/**
 * FitBark OAuth Step #4: Requesting a (Long-Lived) FitBark Access Token.
 *
 * Handles successful FitBark authentication, exchanging the returned temporary authorization code for a long-lived
 * FitBark access token (expires after one year).
 *
 * @param fitBarkAuthorizationCode The temporary authorization code from the FitBark HTTP GET authentication response.
 * @return Whether the HTTP POST request was successful.
 */
boolean getFitBarkAccessToken(String fitBarkAuthorizationCode) {
  logTrace("getFitBarkAccessToken(${fitBarkAuthorizationCode})")
  logInfo("Fetching FitBark OAuth Access Token.")

  // 1. Construct the parameters for the HTTP POST call to request the access token.
  Map requestAccessTokenHTTPParams = [
      uri: "https://app.fitbark.com/oauth/token",
      query: [
          client_id    : fitBarkClientID,
          client_secret: fitBarkClientSecret,
          code         : fitBarkAuthorizationCode,
          grant_type   : "authorization_code",
          redirect_uri : HUBITAT_STATE_REDIRECT_URL
      ]
  ]

  // 2. Send the HTTP POST request.
  try {
    boolean isSuccessFitBarkAccessTokenResult = false
    httpPost(requestAccessTokenHTTPParams) { response ->
      isSuccessFitBarkAccessTokenResult = handleFitBarkAccessTokenResponse(response)
    }
    return isSuccessFitBarkAccessTokenResult
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Access Token request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    return false
  }
}

/**
 * FitBark OAuth Step #4A: Refreshing the (Long-Lived) FitBark Access Token.
 *
 * When the user's FitBark Access Token expires after one year, the stored FitBark Refresh Token can be used to refresh
 * it and request a new FitBark Access Token.
 *
 * @note This can only be called after successfully storing the Access Token and Refresh Token values returned from a
 *       request made with |getFitBarkAccessToken|.
 */
void refreshFitBarkAccessToken() {
  logTrace("refreshFitBarkAccessToken()")

  // 1. Make sure the user has their FitBark Client ID and Client Secret saved.
  if (!fitBarkClientID || !fitBarkClientSecret) {
    logError("Unable to refresh Access Token –– Client Credentials are missing.")
    return
  }

  // 2. Make sure there is a stored Refresh Token from a previous authentication.
  String refreshToken = state.fitBarkRefreshToken
  if (!refreshToken) {
    logError("Unable to refresh Access Token –– the Refresh Token is missing.")
    return
  }

  logInfo("Refreshing the FitBark Access Token using the Refresh Token: ${refreshToken}.")

  // 3. Construct the parameters for the HTTP POST call to refresh the access token.
  Map refreshAccessTokenHTTPParams = [
      uri: "https://app.fitbark.com/oauth/token",
      query: [
          client_id    : fitBarkClientID,
          client_secret: fitBarkClientSecret,
          refresh_token: refreshToken,
          grant_type   : "refresh_token",
      ]
  ]

  try {
    httpPost(refreshAccessTokenHTTPParams) { response -> handleFitBarkAccessTokenResponse(response) }
    sendEvent(name: "FitBark Token Refresh", value: "Success")
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Failed to refresh the Access Token -- ${e.getLocalizedMessage()}: ${e.response.data}")
    sendEvent(name: "FitBark Token Refresh", value: "Failure")
  }
}

/**
 * FitBark OAuth Step #5: Storing the (Long-Lived) FitBark Access Token.
 *
 * Handles a successful HTTP POST request response with the user's long-lasting access token.
 *
 * @note The type of the |fitBarkAccessTokenResponse| parameter is not specified here because Hubitat fails to compile
 * the app when trying to either import or directly declare the |groovyx.net.http.HttpResponseDecorator| class.
 * @param fitBarkAccessTokenResponse The |groovyx.net.http.HttpResponseDecorator| response returned from the FitBark
 * access code HTTP POST request.
 * @return Whether the FitBark Access Token was successfully parsed from the response.
 */
boolean handleFitBarkAccessTokenResponse(fitBarkAccessTokenResponse) {
  logTrace("handleFitBarkAccessTokenResponse()")

  // Double check that the request was successful to be safe (should be handled by |catch| in |getFitBarkAccessToken|).
  if (!fitBarkAccessTokenResponse.isSuccess()) {
    logError("FitBark Access Token request failed. Status: ${fitBarkAccessTokenResponse.getStatusLine()}.")
    return false
  }

  Object responseJSON = fitBarkAccessTokenResponse.getData()
  logTrace("handleFitBarkAccessTokenResponse(success: ${responseJSON})")

  maybeStoreFitBarkRefreshTokenFromResponseJSON(responseJSON)  // Have not successfully gotten a Refresh Token.
  storeFitBarkAccessTokenFromResponseJSON(responseJSON)  // Clears out any stored tokens if |access_token| is missing.

  return hasFitBarkAccessToken()
}

/**
 * Signs out the current authenticated FitBark account, if any, and clears out any stored Access/Refresh Tokens.
 *
 * @note This will also delete any linked devices. Also, this does not revoke any authorizations granted from the
 * FitBark side.
 */
void signOutFitBarkAuthorization() {
  logTrace("signOutFitBarkAuthorization()")

  sendEvent(name: "Signing-Out FitBark Auth", value: "Account First Name: ${state.signedInAccountInfo?.username}")
  logInfo("Signing out current FitBark authorization and deleting linked devices.")

  // 1. Remove any linked FitBark devices.
  deleteAllLinkedFitBarkDogMonitors()

  // 2. Clear out the stored FitBark account authorization tokens or properties, if any.
  clearStoredFitBarkAccountAuthValues()
}

/**
 * Clears out the stored FitBark API Client Credentials, if any.
 */
void deleteFitBarkAPIClientCredentials() {
  logTrace("deleteFitBarkAPIClientCredentials()")

  sendEvent(name: "Delete Stored FitBark API Client Credentials", value: "Client ID: ${fitBarkClientID}")
  logInfo("Deleting stored FitBark API Client Credentials.")

  // 1. Clear out the stored FitBark API Client ID, if any.
  app.clearSetting("fitBarkClientID")

  // 2. Clear out the stored FitBark API Client Secret, if any.
  app.clearSetting("fitBarkClientSecret")

  // 3. Reset the state of the Redirect URI configuration.
  state.fitBarkAPIClientAccessToken = null
  state.hasValidHubitatRedirectURI = false
}

/** -------------------------------------------- **/
/** -------- REDIRECT URI CONFIGURATION -------- **/
/** -------------------------------------------- **/

/**
 * If not yet validated, this section shows a button that will kick-off the validation and update process for the
 * FitBark Client API Redirect URI configuration.
 *
 * @return A map defining a section with the link to request the FitBark API Client Access Token.
 */
Map createFitBarkRedirectURIVerificationSection() {
  logTrace("createFitBarkRedirectURIVerificationSection()")

  // Don't show the Redirect URI Configuration button if:
  // - The user has not yet input valid Client ID and Client Secret values.
  // - The user already authorized their account.
  if (isMissingUserInputFitBarkClientCredentials() || hasFitBarkAccessToken() || state.hasValidHubitatRedirectURI) {
    return [:]
  }

  return section {
    paragraph "The FitBark API needs to be configured to include Hubitat " +
        "(<a href='${HUBITAT_STATE_REDIRECT_URL}'>${HUBITAT_STATE_REDIRECT_URL}</a>) " +
        "in its registered Redirect URIs." +
        "\n\n" +
        "<i>See the Hubitat OAuth docs <a href='https://docs.hubitat.com/index.php?title=App_OAuth'>here</a> " +
        "for additional details.</i>" +
        "\n\n" +
        "This step will verify the FitBark API configuration for the FitBark Client ID and Client Secret that " +
        "you provided and add the Hubitat Redirect URI if it has not already been registered. "

    // Display a button that will request a FitBark Client API Access Token and validate the Redirect URI configuration.
    input(
        name          : "validateRedirectURIConfiguration",
        type          : "button",
        title         : "Validate FitBark Redirect URIs",
        submitOnChange: true
    )
  }
}


/**
 * Creates a Help section with some debug tools for FitBark API Authentication issues.
 *
 * @return A map defining a FitBark Auth Help section.
 */
Map createFitBarkAuthHelpSection() {
  logTrace("createFitBarkAuthHelpSection()")

  // Build an HTML string with a label for the "Reset Credentials" button.
  StringBuilder clickResetCredentialsHTMLStringBuilder = new StringBuilder()
  clickResetCredentialsHTMLStringBuilder << "<font size='-1'>"
  clickResetCredentialsHTMLStringBuilder << "Click the button below to delete and re-enter your FitBark Client Credentials."
  clickResetCredentialsHTMLStringBuilder << "</font>"
  String clickResetCredentialsHTMLString = clickResetCredentialsHTMLStringBuilder.toString()

  // Build an HTML string with a label for the "Reset Redirect URI Config" button.
  StringBuilder clickResetRedirectURIHTMLStringBuilder = new StringBuilder()
  clickResetRedirectURIHTMLStringBuilder << "<font size='-1'>"
  clickResetRedirectURIHTMLStringBuilder << "If you're hitting authorization failures, there may be a formatting error in your current FitBark API Redirect URI configuration."
  clickResetRedirectURIHTMLStringBuilder << "</font><br>"
  clickResetRedirectURIHTMLStringBuilder << "<font size='-1'>"
  clickResetRedirectURIHTMLStringBuilder << "Click the button below to overwrite your existing FitBark API Redirect URI value(s) with the Hubitat URL.</p>"
  clickResetRedirectURIHTMLStringBuilder << "</font>"
  String clickResetRedirectURIHTMLString = clickResetRedirectURIHTMLStringBuilder.toString()

  return section(hideable: true, hidden: true, "Help") {
    paragraph clickResetCredentialsHTMLString

    // Create a "Reset Credentials" button to allow the user to reset their input FitBark API Client Credentials without
    // uninstalling and reinstalling the app.
    input(
        name: "deleteFitBarkAPIClientCredentials",
        type: "button",
        title: "Reset FitBark API Client Credentials",
        submitOnChange: true
    )

    if (state.fitBarkAPIClientAccessToken) {
      createDividerParagraph()

      paragraph clickResetRedirectURIHTMLString

      // Create a button that will overwrite the existing FitBark API Redirect URI Configuration to only the Hubitat
      // URL, to provide a clean slate as a fallback for any formatting issues that may cause failures.
      input(
          name: "overwriteFitBarkRedirectURIConfiguration",
          type: "button",
          title: "Reset FitBark API Redirect URI Configuration",
          submitOnChange: true
      )
    }

    createDividerParagraph()

    createDescriptionParagraph(
        "Click the button below for debug actions, logging configuration, and FitBark credentials.")

    createGoToPageButton("debugPage", "Go to App Debugging Tools")
  }
}

/**
 * FitBark Redirect URI Configuration Step #1: FitBark API Client Access Token.
 *
 * Requests the FitBark Client API Access Token with an HTTP POST request, then passes the response to the handler.
 *
 * @note The FitBark API Client Access Token is only used to configure the Redirect URI for the FitBark Client API, it
 * does not provide any account access.
 */
void requestFitBarkAPIClientAccessToken() {
  logTrace("requestFitBarkAPIClientAccessToken()")

  // 1. Construct the parameters for the HTTP POST call to request the FitBark API Client Access Token.
  Map requestClientAccessTokenHTTPParams = [
      uri: "https://app.fitbark.com/oauth/token",
      contentType: "application/json",
      query: [
          client_id    : fitBarkClientID,
          client_secret: fitBarkClientSecret,
          scope        : "fitbark_open_api_2745H78RVS",
          grant_type   : "client_credentials"
      ]
  ]

  // 2. Create a local variable in which to store the Client Access Token from the response.
  String clientAccessToken = null

  // 3. Send the HTTP POST request.
  try {
    httpPost(requestClientAccessTokenHTTPParams) { response ->
      Object responseJSON = response.getData()
      logInfo("Received Client Access Token request response: ${responseJSON}.")

      clientAccessToken = responseJSON.access_token?.trim()
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Client Access Token request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    return
  }

  // 4. Verify the HTTP POST response contained the expected Access Token.
  if (!clientAccessToken) {
    logError("Missing Client Access Token in response.")
    return
  }
  logDebug("Got Client Access Token, reading current Redirect URIs.")
  state.fitBarkAPIClientAccessToken = clientAccessToken

  // 5. Read the existing Redirect URI configuration using the fetched Client Access Token.
  String currentRedirectURIResponse = readCurrentClientRedirectURIs(clientAccessToken)
  if (!currentRedirectURIResponse) {
    logError("Missing Current Redirect URI from response.")
    return
  }

  // 6. Check whether the Redirect URI configuration needs to be updated (|readCurrentClientRedirectURIs| stored the
  // validation result in the App State in |hasValidHubitatRedirectURI|).
  if (state.hasValidHubitatRedirectURI) {
    logDebug("Current Redirect URI configuration already includes Hubitat, no updates necessary.")
    return
  }

  // 6. Add the required Hubitat URI to the Client Redirect URI configuration.
  updateClientRedirectURIsWithRequiredHubitatURL(clientAccessToken, currentRedirectURIResponse)

  // 7. Read the existing Redirect URI configuration once again, to check that the update was successful.
  readCurrentClientRedirectURIs(clientAccessToken)
}

/**
 * FitBark Redirect URI Configuration Step #2: Read Current FitBark API Redirect URIs.
 *
 * Requests the current configured Redirect URIs for the FitBark API Client, to check whether the Hubitat Redirect URI
 * has already been configured or not.
 *
 * @param fitBarkAPIClientAccessToken The Client Access Token fetched from |requestFitBarkAPIClientAccessToken|.
 * @return The current Client Redirect URI response string (a list of Redirect URIs joined by the separator '/r', or
 * null if a Redirect URI response was not read successfully.
 */
String readCurrentClientRedirectURIs(String fitBarkAPIClientAccessToken) {
  logTrace("readCurrentClientRedirectURIs(${makePartiallyCensoredString(fitBarkAPIClientAccessToken)})")

  if (!fitBarkAPIClientAccessToken) {
    logError("Expected non-null FitBark API Client Access Token.")
    return null
  }

  // 1. Construct the parameters for the HTTP GET call to request the current configured FitBark API Redirect URIs.
  Map readCurrentRedirectURIsHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/redirect_urls",
      headers: [
          "Authorization" : "Bearer " + fitBarkAPIClientAccessToken
      ]
  ]

  // 2. Create a local variable in which to store the Client Access Token from the response.
  String currentRedirectURIResponse = null

  // 3. Send the HTTP POST request.
  try {
    httpGet(readCurrentRedirectURIsHTTPParams) { response ->
      Object responseJSON = response.getData()
      logInfo("Received Current Redirect URIs request response: ${responseJSON}.")
      currentRedirectURIResponse = responseJSON.redirect_uri
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logHttpResponseException("Current Redirect URIs request failed", e)
    return null
  }

  // 4. Parse the Redirect URIs response string into a list to check its contents - the |redirect_uri| returned in the
  // response is a string containing all configured Redirect URIs joined by the separator '\r', i.e.:
  // "redirect_uri":"urn:ietf:wg:oauth:2.0:oob\rhttp://some.url.com/hello\rhttp://some.other.com/redirect".
  List<String> currentRedirectURIs = currentRedirectURIResponse?.split(" ")
  logDebug("Got ${currentRedirectURIs.size()} currentRedirectURIs: ${currentRedirectURIResponse}, parsed to: ${currentRedirectURIs}.")

  // 5. Check whether the existing Redirect URI configuration contains the required Hubitat URL.
  boolean currentRedirectURIsContainsRequiredURL = currentRedirectURIs?.contains(HUBITAT_STATE_REDIRECT_URL)
  logDebug("Current Redirect URIs: ${currentRedirectURIs}.")
  logDebug("Current contains URL: ${HUBITAT_STATE_REDIRECT_URL}? ${currentRedirectURIsContainsRequiredURL}.")

  // 5. Update the state with the current validation status.
  state.hasValidHubitatRedirectURI = currentRedirectURIsContainsRequiredURL

  // 6. Return the existing Redirect URI configuration in case it needs to be updated.
  return currentRedirectURIResponse
}

/**
 * FitBark Redirect URI Configuration Step #3A: Update the FitBark API Redirect URI Configuration.
 *
 * Updates the configured Redirect URIs for the FitBark API Client to include the Hubitat Redirect URI.
 *
 * @param clientAccessToken The FitBark API Client Access Token fetched from |requestFitBarkAPIClientAccessToken|.
 * @param currentRedirectURIResponse The current Redirect URI response fetched from |readCurrentClientRedirectURIs|.
 */
void updateClientRedirectURIsWithRequiredHubitatURL(String clientAccessToken, String currentRedirectURIResponse) {
  logTrace("updateClientRedirectURIsWithRequiredHubitatURL(${clientAccessToken}, ${currentRedirectURIResponse})")

  if (!clientAccessToken || !currentRedirectURIResponse) {
    logError("Expected non-null Client Access Token and non-null Client Redirect URI parameter.")
    return
  }

  // 1. Create the updated Redirect URI configuration.
  String updatedRedirectURIConfig = currentRedirectURIResponse + " " + HUBITAT_STATE_REDIRECT_URL
  logDebug("Updating existing Redirect URI Config: ${currentRedirectURIResponse} to ${updatedRedirectURIConfig}")

  // 2. Dispatch the HTTP POST request to update the configuration.
  updateClientRedirectURIs(clientAccessToken, updatedRedirectURIConfig)
}

/**
 * FitBark Redirect URI Configuration Step #3B: Update the FitBark API Redirect URI Configuration.
 *
 * Updates the configured Redirect URIs for the FitBark API Client using the provided input.
 *
 * @param clientAccessToken The FitBark API Client Access Token fetched from |requestFitBarkAPIClientAccessToken|.
 * @param updatedRedirectURIConfig The updated Redirect URI values to configure.
 */
void updateClientRedirectURIs(String clientAccessToken, String updatedRedirectURIConfig) {
  logTrace("updateClientRedirectURIs(Token: ${clientAccessToken}, Updated URIs: '${updatedRedirectURIConfig}'.)")

  if (!clientAccessToken || !updatedRedirectURIConfig) {
    logError("Expected non-null/empty Client Access Token and non-null/empty Client Redirect URI Update value.")
    return
  }

  logDebug("Updating Redirect URI Config to '${updatedRedirectURIConfig}'.")

  // 1. Construct the parameters for the HTTP POST call to update FitBark API Redirect URI configuration.
  Map updateRedirectURIHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/redirect_urls",
      contentType: "application/json",
      headers: [
          "Authorization" : "Bearer " + clientAccessToken
      ],
      query: [
          redirect_uri    : updatedRedirectURIConfig
      ]
  ]

  // 3. Send the HTTP POST request.
  try {
    httpPost(updateRedirectURIHTTPParams) { response ->
      Object responseJSON = response.getData()
      logInfo("Success! Received updated Redirect URIs response: '${responseJSON.redirect_uri_set}'.")
      sendEvent(name: "Updated Client Redirect URIs", value: responseJSON.redirect_uri_set)
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Update Redirect URIs request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    sendEvent(name: "Updated Client Redirect URIs", value: e.getLocalizedMessage())
  }
}

/** ---------------------------- **/
/** -------- USER STATE -------- **/
/** ---------------------------- **/

/**
 * Creates a section displaying the App's FitBark authorization status.
 *
 * @return A map defining a FitBark Auth Status section.
 */
Map createFitBarkAuthStatusSection() {
  logTrace("createFitBarkAuthStatusSection()")

  if (!hasFitBarkAccessToken()) {
    logError("createFitBarkAuthStatusSection called with no Access Token stored.")
    return [:]
  }

  Map signedInAccountInfo = readFitBarkUserInfo(state.fitBarkAccessToken)
  state.signedInAccountInfo = signedInAccountInfo

  boolean isTokenExpiringInNextMonth = false
  if (state.fitBarkAccessTokenExpirationTimestamp) {
    use(groovy.time.TimeCategory) {
      Date expirationDate = new Date(state.fitBarkAccessTokenExpirationTimestamp)
      Date oneMonthWarningDate = 1.months.from.now

      isTokenExpiringInNextMonth = expirationDate < oneMonthWarningDate
    }
  }

  return section(hideable: true, hidden: false, "Current Authorization State") {
    paragraph "<b>Status:</b> Signed In ✅"
    paragraph "<b>Username:</b> ${signedInAccountInfo.username}"
    paragraph "<b>Name:</b> ${signedInAccountInfo.name}"

    if (isTokenExpiringInNextMonth) {
      String expirationDateString = "[Unknown]"
      use(groovy.time.TimeCategory) {
        Date expirationDate = new Date(state.fitBarkAccessTokenExpirationTimestamp)
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("EEE, d MMM yyyy")
        expirationDateString = dateFormat.format(expirationDate)
      }
      paragraph "Expiration Date: ${expirationDateString} – ⚠️ Current sign-in expiring within the next month. ⚠️"
    }

    createFillerParagraph()

    input(
        name: "signOutFitBark",
        type: "button",
        title: "Log Out Current FitBark Account",
        submitOnChange: true
    )
    paragraph "<i>Note: Logging out will also remove and delete any connected FitBark devices.</i>"
  }
}

/**
 * Returns information about the FitBark user associated with the input Access Token.
 *
 * @param fitBarkUserAccessToken The Access Token for the account whose information should be read.
 * @return A map with information about the given account, if any.
 */
Map readFitBarkUserInfo(String fitBarkUserAccessToken) {
  logTrace("readFitBarkUserInfo()")

  if (!fitBarkUserAccessToken) {
    logError("readFitBarkUserInfo called with null FitBark Access Token.")
    return null
  }

  Map readCurrentUserInfoHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/user",
      headers: [
          "Authorization" : "Bearer " + fitBarkUserAccessToken
      ]
  ]

  String signedInAccountUsername = null
  String signedInAccountName = null
  String signedInAccountFirstName = null

  try {
    httpGet(readCurrentUserInfoHTTPParams) { response ->
      Object responseJSON = response.getData()
      logInfo("Received Get User Info request response: ${responseJSON}.")

      signedInAccountUsername = responseJSON.user?.username
      signedInAccountName = responseJSON.user?.name
      signedInAccountFirstName = responseJSON.user?.first_name
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logHttpResponseException("Get User Info request failed", e)
    return null
  }

  return [
      username: signedInAccountUsername,
      name: signedInAccountName,
      firstName: signedInAccountFirstName
  ]
}

/** ------------------------------ **/
/** -------- DEVICE STATE -------- **/
/** ------------------------------ **/

/**
 * Creates the Device Discovery Page configuration for the FitBark Hubitat App.
 *
 * @return A map defining the Discovery Page.
 */
Map deviceDiscoveryPage() {
  logTrace("deviceDiscoveryPage()")
  logDebug("######## Loading the App Device Discovery Page. ########")

  if (!state.deviceDiscovery?.didStart) {
    logDebug("Initial Device Discovery Page Load. Kicking off Discovery Request.")
    state.deviceDiscovery = [
        didStart: true,
        newlyDiscoveredCount: 0,
        alreadyDiscoveredCount: 0,
        lastRunTimestamp: (new Date()).toString()
    ]
    logDebug("Current state.deviceDiscovery map: ${state.deviceDiscovery}.")
    discoverLinkedFitBarkDogMonitors()
  }

  if (!state.deviceDiscovery.didFinish && !state.deviceDiscovery.failureMessage) {
    logTrace("Showing auto-refreshing Device Discovery page.")

    // Add an extra progress dot each second.
    state.deviceDiscovery.progressEllipsis = (state.deviceDiscovery.progressEllipsis ?: "") + "."

    return dynamicPage(name: "deviceDiscoveryPage", refreshInterval: 1) {
      createHeaderSection("FitBark Device Discovery")
      section {
        paragraph "<b>Discovering Devices..${state.deviceDiscovery.progressEllipsis}</b>"
        createDividerParagraph()
      }
      createCurrentlyLinkedFitBarkDogMonitorsSection()
      createDividerSection()
      section {
        createGoToPageButton("mainPage", "Cancel")
      }
    }
  }

  // Discovery has finished. Stop refreshing the page and reset the device discovery in the App State for next time.
  logTrace("Showing Device Discovery finished/failed page (state.deviceDiscovery: ${state.deviceDiscovery}).")
  String discoveryFailureMessage = state.deviceDiscovery.failureMessage
  int newlyDiscoveredDeviceCount = state.deviceDiscovery.newlyDiscoveredCount
  int alreadyDiscoveredDeviceCount = state.deviceDiscovery.alreadyDiscoveredCount
  state.deviceDiscovery = [lastRunTimestamp: state.deviceDiscovery.lastRunTimestamp]

  return dynamicPage(name: "deviceDiscoveryPage") {
    createHeaderSection("FitBark Device Discovery")
    section {
      if (discoveryFailureMessage) {
        logError("Failure during Device Discovery: ${discoveryFailureMessage}.")
        paragraph """<b>Device Discovery Failure</b>
        ${discoveryFailureMessage}."""
      } else {
        paragraph """ <b>Device Discovery Complete</b>
          • ${newlyDiscoveredDeviceCount} Newly Discovered Device(s).
          • ${alreadyDiscoveredDeviceCount} Already Discovered Device(s)."""
      }
      createDividerParagraph()
    }

    createCurrentlyLinkedFitBarkDogMonitorsSection()

    section {
      createDividerParagraph()
      createGoToPageButton("mainPage", "Done")
    }
  }
}

/**
 * Creates a section displaying description text for the link FitBark devices state.
 *
 * @return A map defining the Linked Dogs Status section.
 */
Map createLinkedDevicesDescriptionSection() {
  logTrace("createLinkedDevicesDescriptionSection()")

  // Don't show this section until user has authorized their account.
  if (!hasFitBarkAccessToken()) {
    return [:]
  }

  Map linkedDevices = getLinkedFitBarkDevices()

  // Don't show this section if there are no linked dogs.
  if (linkedDevices.all.isEmpty()) {
    return [:]
  }

  boolean hasMoreThanOneDog = linkedDevices.all.size() > 1
  String lastDeviceDiscoveryTimestamp = state.deviceDiscovery?.lastRunTimestamp

  return section {
    paragraph """\
      ✅ You currently have ${linkedDevices.all.size()} linked FitBark ${hasMoreThanOneDog ? "devices" : "device"}.
      <p><i>[Owned Devices: ${linkedDevices.owned.size()}, Followed Devices: ${linkedDevices.followed.size()}]</i></p>
      """.stripIndent()

    if (lastDeviceDiscoveryTimestamp) {
      createDescriptionParagraph(
          "The last time you ran device discovery was at ${lastDeviceDiscoveryTimestamp}.")
    }
  }
}

/**
 * Creates a section displaying a button to initiate FitBark Dog Discovery.
 *
 * @return A map defining the Discover Linked Dogs section.
 */
Map createDiscoverLinkedFitBarkDogMonitorsSection() {
  logTrace("createDiscoverLinkedFitBarkDogMonitorsSection()")

  // Don't show Dog Discovery until user has authorized their account.
  if (!hasFitBarkAccessToken()) {
    return [:]
  }

  Map discoveryButtonText = [:]
  if (hasLinkedFitBarkDogMonitors()) {
    discoveryButtonText.title = "Discover More FitBark Devices"
    discoveryButtonText.description = "Connect any new devices added to your FitBark account since " +
        "you last ran device discovery.\n\n" +
        "<i>To re-run the discovery with a clean slate, go to the Debugging Tools page to delete all linked devices.</i>"
  } else {
    discoveryButtonText.title = "Connect Your FitBark Devices"
    discoveryButtonText.description = "Link the FitBark devices from your FitBark account to Hubitat."
  }

  // Reset the Device Discovery state before starting Discovery, in case Discovery was cancelled before cleanup was run.
  if (state.deviceDiscovery) {
    state.deviceDiscovery = [lastRunTimestamp: state.deviceDiscovery.lastRunTimestamp]
  }

  return section {
    if (!hasLinkedFitBarkDogMonitors()) {
      paragraph """⚠️ NOTE: The Driver Code for the \
<a href='https://github.com/midair/hubitat-fitbark/blob/main/drivers/Fitbark-Dog-Activity-Monitor.groovy'>\
FitBark Dog Activity Monitor</a> must be installed before \
discovering your FitBark devices.

"""
    }

    createDescriptionParagraph(discoveryButtonText.description)
    createGoToPageButton("deviceDiscoveryPage", discoveryButtonText.title)
  }
}

/**
 * Creates a section displaying all linked FitBark dogs.
 *
 * @return A map defining the linked dogs section.
 */
Map createCurrentlyLinkedFitBarkDogMonitorsSection() {
  logTrace("createCurrentlyLinkedFitBarkDogMonitorsSection()")

  // Don't show linked monitors until user has authorized their account.
  if (!hasFitBarkAccessToken()) {
    return [:]
  }

  // Get a list containing all FitBark Dog Monitors linked to this app.
  List<ChildDeviceWrapper> linkedFitBarkDevices = getChildDevices()
  logDebug("Linked FitBark Dog Monitors: ${linkedFitBarkDevices}.")

  // Configure the content to reflect whether any devices have been linked yet.
  StringBuilder linkedDevicesHTMLStringBuilder = new StringBuilder()

  if (linkedFitBarkDevices.isEmpty()) {

    linkedDevicesHTMLStringBuilder << "<p>You don't have any linked FitBark devices yet.</p>"
  } else {

    // TODO: Consider sorting devices by relationship type.

    // Build a string containing an unordered list displaying a link to edit each connected FitBark device.
    linkedDevicesHTMLStringBuilder << "<ul>"
    linkedFitBarkDevices.each { linkedFitBarkDevice ->
      if (linkedFitBarkDevice) {
        String deviceId = linkedFitBarkDevice.getId()
        String deviceLabel = linkedFitBarkDevice.getLabel()
        String deviceBatteryLevel = linkedFitBarkDevice.currentValue("battery")

        Integer totalActivityPtsToday = linkedFitBarkDevice.currentValue("steps")
        Integer goalActivityPtsToday = linkedFitBarkDevice.currentValue("goal")
        Integer goalPercentComplete = linkedFitBarkDevice.currentValue("percentageOfDailyGoalComplete")

        String lastFitBarkSyncDateStr = linkedFitBarkDevice.currentValue("lastDeviceSyncToFitBark")
        String durationSinceLastDeviceSyncStr = "[Unknown]"
        if (lastFitBarkSyncDateStr) {
          durationSinceLastDeviceSyncStr = ""

          use(groovy.time.TimeCategory) {
            SimpleDateFormat lastFitBarkSyncTimeFormatter = new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy")
            Date lastTimeFitBarkSyncedDevice = lastFitBarkSyncTimeFormatter.parse(lastFitBarkSyncDateStr)
            def duration = new Date() - lastTimeFitBarkSyncedDevice
            durationSinceLastDeviceSyncStr = duration.toString()
          }
        }

        linkedDevicesHTMLStringBuilder << "<br><br><li><font size='+1'>"
        linkedDevicesHTMLStringBuilder << "<a href='/device/edit/${deviceId}'>${deviceLabel}</a> – "
        linkedDevicesHTMLStringBuilder << "<b>Today's Activity Progress: "
        linkedDevicesHTMLStringBuilder << "${totalActivityPtsToday} out of ${goalActivityPtsToday} pts "
        linkedDevicesHTMLStringBuilder << "(${goalPercentComplete}% of the total goal).</b>"
        linkedDevicesHTMLStringBuilder << "<br><i>Device battery level: ${deviceBatteryLevel}%.</i>"
        linkedDevicesHTMLStringBuilder << "<br><i>Time since last sync: ${durationSinceLastDeviceSyncStr}.</i>"
        linkedDevicesHTMLStringBuilder << "</li></font>"
      }
    }
    linkedDevicesHTMLStringBuilder << "</ul>"
  }

  String linkedDevicesSectionContent = linkedDevicesHTMLStringBuilder.toString()
  return section(hideable: true, hidden: false, "Linked FitBark Dog Activity Monitors") {
    paragraph linkedDevicesSectionContent

    if (!linkedFitBarkDevices.isEmpty()) {
      createFillerParagraph()
      createDividerParagraph()

      input(
          name: "refreshAllLinkedDevicesButton",
          type: "button",
          title: "Refresh All Linked Devices",
          submitOnChange: true
      )
      paragraph "<p>Devices default to polling FitBark every 30 minutes. The button above will manually trigger linked " +
          "devices to immediately update their values.</p>"
    }
  }
}

/**
 * Performs FitBark Dog Discovery for any dogs linked to the currently signed-in account.
 */
void discoverLinkedFitBarkDogMonitors() {
  logTrace("discoverLinkedFitBarkDogMonitors()")

  // Don't show Dog Discovery until user has authorized their account.
  if (!hasFitBarkAccessToken()) {
    logError("discoverLinkedFitBarkDogMonitors called with no stored FitBark Access Token.")
    state.deviceDiscovery.failureMessage = "Missing FitBark Access Token."
    return
  }

  // Get the list of any already-discovered devices to avoid re-adding them again.
  List<String> alreadyDiscoveredDeviceIDs = getChildDevices().collect { return it.getDeviceNetworkId() }
  logInfo("Starting FitBark Monitor Discovery (already linked device IDs: ${alreadyDiscoveredDeviceIDs}).")

  Map getDogRelationsHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/dog_relations",
      headers: [ "Authorization" : "Bearer " + state.fitBarkAccessToken ]
  ]
  asynchttpGet(
      handleDiscoveredDogRelations,                             // Callback handler method.
      getDogRelationsHTTPParams,                                // HTTP GET call parameters.
      [ existingDeviceNetworkIDs: alreadyDiscoveredDeviceIDs ]  // Additional data for the callback method.
  )
}

/**
 * Handles the response from an HTTP GET request for the user's Dog Relations.
 *
 * @param getLinkedDogsResponse A |groovyx.net.http.HttpResponseDecorator|/|AsyncResponse| containing the FitBark API response to
 * the request for Dog Relation information.
 * @param additionalData Additional data passed from the original caller.
 */
void handleDiscoveredDogRelations(getLinkedDogsResponse, additionalData) {
  logTrace("handleDiscoveredDogRelations(hasError: ${getLinkedDogsResponse.hasError()})")

  // 1. Make sure the HTTP request was successful.
  if (getLinkedDogsResponse.hasError()) {
    // TODO: Handle specific error codes, i.e. trying the refresh token after 401 (Unauthorized).
    logError("Get Linked Dogs Request Failed (Status Code: ${getLinkedDogsResponse.getStatus()}) -- ${getLinkedDogsResponse.getErrorMessage()}")
    state.deviceDiscovery.failureMessage = "${getLinkedDogsResponse.getErrorMessage()}"
    return
  }

  // 2. Parse the |dog_relations| from the response.
  List<Object> fetchedDogRelationships = null
  try {
    Object responseJSON = getLinkedDogsResponse.getJson()
    fetchedDogRelationships = responseJSON?.dog_relations
  } catch (e) {
    logError("Failed to parse JSON from Dog Relations response (${getLinkedDogsResponse.getData()}) – ${e}.")
    state.deviceDiscovery.failureMessage = "${e}"
    return
  }

  // 3. Verify that the response JSON is non-null and contains the expected |dog_relations| parameter.
  if (fetchedDogRelationships == null) {
    state.deviceDiscovery.failureMessage = "Invalid |dog_relations| JSON Response: ${responseJSON}."
    return
  }
  if (fetchedDogRelationships.isEmpty()) {
    logWarn("No dogs connected to the current FitBark user ('${state.signedInAccountInfo?.username}').")
    state.deviceDiscovery.failureMessage = "Unable to find any devices associated with your FitBark account. " +
        "You'll need to make sure you are a FitBark Owner or Follower of at least one dog before you can continue."
    return
  }
  List<String> existingDeviceNetworkIDs = additionalData.existingDeviceNetworkIDs
  logDebug("Fetched FitBark Dogs: ${fetchedDogRelationships}. Existing Dog Device IDs: ${existingDeviceNetworkIDs}")

  for (dogRelationship in fetchedDogRelationships) {

    if (!dogRelationship.dog?.slug) {
      logError("Expected dog relationship to have a |dog| with a |slug| value: ${dogRelationship}.")
      state.deviceDiscovery.failureMessage = "Invalid Dog Relationship format: ${dogRelationship} (missing Dog Slug)."
      return
    }

    LinkedHashMap<Object, Object> linkedDogMonitor = [:]
    linkedDogMonitor.userRelationship = dogRelationship.status?.toString()  // |status| is either 'OWNER' or 'FRIEND'.
    linkedDogMonitor.dogName = dogRelationship.dog.name?.toString()         // |name| is the name in the dog's profile.
    linkedDogMonitor.slug = dogRelationship.dog.slug?.toString()            // |slug| is the dog's UUID.

    if (existingDeviceNetworkIDs.contains(linkedDogMonitor.slug)) {
      logDebug("Device for ${linkedDogMonitor.dogName} already discovered, not re-adding: ${dogRelationship.dog}.")
      state.deviceDiscovery.alreadyDiscoveredCount += 1
      continue
    }

    if (settings?.onlyDiscoverOwnedDogs && linkedDogMonitor.userRelationship != "OWNER") {
      logDebug("Filtering non-Owner device from discovery results: ${linkedDogMonitor}.")
      continue
    }

    ChildDeviceWrapper addedChildFitBarkDevice = addChildDogActivityMonitorDevice(linkedDogMonitor)
    if (!addedChildFitBarkDevice) {
      // If |addChildDogActivityMonitorDevice| returns null it should've already set the failure message.
      state.deviceDiscovery.failureMessage = state.deviceDiscovery.failureMessage ?: "Add Child Device failed."
      return
    }

    // For now, configure User Relationship here with the assumption that it won't be updated in the device's lifetime.
    addedChildFitBarkDevice.sendEvent([name: "userRelationshipType", value: linkedDogMonitor.userRelationship])

    // Configure the other attributes which will be refreshed on a polling interval.
    configureDogMonitorAttributes(addedChildFitBarkDevice, dogRelationship.dog)

    state.deviceDiscovery.newlyDiscoveredCount += 1
  }

  logInfo("Device Discovery Completed! Added ${state.deviceDiscovery.newlyDiscoveredCount} new devices.")
  state.deviceDiscovery.didFinish = true
}

/**
 * Adds a linked FitBark Dog Activity Monitor to the App's Child Devices.
 *
 * @param linkedDogMonitor Map of device properties, including the dog's name and the device ID.
 * @return The newly added Child Device, if successfully added – null otherwise.
 */
ChildDeviceWrapper addChildDogActivityMonitorDevice(LinkedHashMap<Object, Object> linkedFitBarkMonitor) {
  logTrace("addChildDogActivityMonitorDevice(linkedFitBarkMonitor: ${linkedFitBarkMonitor})")

  String deviceNetworkId = linkedFitBarkMonitor.slug
  String deviceDisplayLabel = linkedFitBarkMonitor.dogName + "'s FitBark Device"
  Map properties = [ label: deviceDisplayLabel ]

  ChildDeviceWrapper newlyAddedChildDevice = null
  try {
    newlyAddedChildDevice = addChildDevice(FITBARK_GROOVY_NAMESPACE, FITBARK_DRIVER_TYPE, deviceNetworkId, properties)
  } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
    state.deviceDiscovery.failureMessage = "${e.message}. Please install the ${FITBARK_DRIVER_TYPE} driver."
    return null
  }
  if (!newlyAddedChildDevice) {
    state.deviceDiscovery.failureMessage = "Unknown failure adding Child Device for ${linkedFitBarkMonitor}."
    return null
  }

  logInfo("Successfully added Child Device for ${deviceDisplayLabel}.")
  sendEvent(name: "Added Child Device", value: deviceDisplayLabel)

  return newlyAddedChildDevice
}

/**
 * Configures the attributes of a linked FitBark Dog Activity Monitor to reflect the current device state.
 *
 * @param deviceToConfigure The Child Device whose attributes should be configured.
 * @param dogDetailsJSON The parsed JSON for a FitBark API 'dog' object that should has the current device values.
 */
void configureDogMonitorAttributes(DeviceWrapper deviceToConfigure, Object dogDetailsJSON) {
  logTrace("configureDogMonitorAttributes(dogDetailsJSON: ${dogDetailsJSON})")

  // => Update the Battery Capability attributes.
  deviceToConfigure.sendEvent([name: "battery", value: dogDetailsJSON.battery_level.toInteger()])

  // => Update the Step Sensor Capability attributes.
  Integer dailyActivityGoal = dogDetailsJSON.daily_goal.toInteger()
  Integer todaysActivityProgress = dogDetailsJSON.activity_value.toInteger()

  deviceToConfigure.sendEvent([name: "goal", value: dailyActivityGoal, unit: "BarkPoints"])
  deviceToConfigure.sendEvent([name: "steps", value: todaysActivityProgress, unit: "BarkPoints"])

  // => Update the daily goal progress percentages.
  Integer oldDailyGoalPercentage = deviceToConfigure.currentValue("percentageOfDailyGoalComplete")
  Integer newDailyGoalPercentage = (todaysActivityProgress / dailyActivityGoal) * 100
  if (newDailyGoalPercentage < oldDailyGoalPercentage) {
    // Activity progress will only decrease when it resets at the start of the day.
    deviceToConfigure.sendEvent([name: "percentageOfDailyGoalCompletedYesterday", value: oldDailyGoalPercentage, unit: "%"])
  }
  deviceToConfigure.sendEvent([name: "percentageOfDailyGoalComplete", value: newDailyGoalPercentage, unit: "%"])

  // => Update the average hourly activity.
  if (dogDetailsJSON.hourly_average) {
    deviceToConfigure.sendEvent([name: "hourlyAverageActivityPoints", value: dogDetailsJSON.hourly_average.toInteger()])
  }

  // => Update the Play/Rest/Active Time Counters.
  deviceToConfigure.sendEvent([name: "minutesTodayPlayTime", value: dogDetailsJSON.min_play.toInteger(), unit: "minutes"])
  deviceToConfigure.sendEvent([name: "minutesTodayActiveTime", value: dogDetailsJSON.min_active.toInteger(), unit: "minutes"])
  deviceToConfigure.sendEvent([name: "minutesTodayRestTime", value: dogDetailsJSON.min_rest.toInteger(), unit: "minutes"])

  // => Update the dog's name.
  String dogNameValue = dogDetailsJSON.name ?: (deviceToConfigure.currentValue("dogName") ?: "Mystery Pup")
  deviceToConfigure.sendEvent([name: "dogName", value: dogNameValue])

  // => Update the dog's birthday.
  if (dogDetailsJSON.birth) {
    SimpleDateFormat birthdayFormatter = new SimpleDateFormat("yyyy-MM-dd")
    Date birthdayDate = birthdayFormatter.parse(dogDetailsJSON.birth)
    deviceToConfigure.sendEvent([name: "dogBirthday", value: birthdayDate])
  }

  // => Update the time that the dog monitor's last synced to the FitBark servers.
  //
  // Note: The 'Get Dog Info' endpoint returns the Last Sync time under the |last_sync| key, but the 'Get User Related
  // Dogs' endpoint returns it under the |activity_date| key.
  if (dogDetailsJSON.last_sync || dogDetailsJSON.activity_date) {
    SimpleDateFormat lastSyncTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    Date lastSyncTime = lastSyncTimeFormatter.parse(dogDetailsJSON.last_sync ?: dogDetailsJSON.activity_date)
    deviceToConfigure.sendEvent([name: "lastDeviceSyncToFitBark", value: lastSyncTime])
  }
}

/**
 * Refreshes all linked FitBark Devices.
 */
void refreshAllLinkedFitBarkDogMonitors() {
  logTrace("refreshAllLinkedFitBarkDogMonitors()")

  List<ChildDeviceWrapper> linkedFitBarkDogMonitors = getChildDevices()

  logInfo("Refreshing all child FitBark devices: ${linkedFitBarkDogMonitors}.")
  linkedFitBarkDogMonitors.each { childDevice ->
    if (childDevice != null) {
      handleDeviceRefresh(childDevice)
    }
  }
}

/**
 * Deletes all linked FitBark devices.
 */
void deleteAllLinkedFitBarkDogMonitors() {
  logTrace("deleteAllLinkedFitBarkDogMonitors()")

  List<ChildDeviceWrapper> linkedFitBarkDogMonitors = getChildDevices()
  int linkedMonitorsToDeleteCount = linkedFitBarkDogMonitors.size()

  logInfo("Deleting all ${linkedMonitorsToDeleteCount} child FitBark devices: ${linkedFitBarkDogMonitors}.")
  linkedFitBarkDogMonitors.each { childDevice ->
    if (childDevice != null) {
      deleteChildDevice(childDevice.getDeviceNetworkId())
    }
  }

  sendEvent(name: "Deleted All Linked Devices", value: "${linkedMonitorsToDeleteCount} Device(s) Deleted")
}

/** -------------------------------- **/
/** -------- DEVICE UPDATES -------- **/
/** -------------------------------- **/

/**
 * Handles a device request to refresh the device details.
 *
 * @param deviceToUpdate The device requesting the update.
 */
void handleDeviceRefresh(DeviceWrapper deviceToRefresh) {
  String deviceLabel = deviceToRefresh.getLabel()
  logTrace("handleDeviceRefresh(deviceToRefresh: ${deviceLabel})")

  if (!deviceToRefresh) {
    logError("handleDeviceRefresh expected non-null deviceToRefresh.")
    return
  }

  String fitBarkDogSlug = deviceToRefresh.getDeviceNetworkId()

  // 1. Construct the parameters for the HTTP GET call to request dog information.
  Map getDogInfoHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/dog/" + fitBarkDogSlug,
      headers: [ "Authorization" : "Bearer " + state.fitBarkAccessToken ]
  ]

  // 2. Send the HTTP GET request.
  logDebug("Sending a GET request to fetch Dog Info for ${deviceLabel} (${fitBarkDogSlug}).")
  try {
    httpGet(getDogInfoHTTPParams) { response ->
      Object responseJSON = response.getData()
      configureDogMonitorAttributes(deviceToRefresh, responseJSON.dog)
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Get Dog Info request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
  }

  // 3. Construct the parameters for the HTTP GET call to request the daily goal details.
  Map getDailyGoalsHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/daily_goal/" + fitBarkDogSlug,
      headers: [ "Authorization" : "Bearer " + state.fitBarkAccessToken ]
  ]

  // 4. Send the HTTP GET request.
  logDebug("Sending a GET request to fetch Daily Goals for ${deviceLabel} (${fitBarkDogSlug}).")
  try {
    httpGet(getDailyGoalsHTTPParams) { response ->
      Object responseJSON = response.getData()
      setScheduledDailyGoalsAttributeOnDevice(deviceToRefresh, responseJSON)
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Fetch Daily Goals request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
  }
}

/**
 * Handles getting the scheduled Daily Goal changes for the given device.
 *
 * @param deviceToUpdate The device requesting the information.
 */
void handleGetDailyGoalUpdates(DeviceWrapper requestingDevice) {
  String deviceLabel = requestingDevice?.getLabel()
  logTrace("handleGetDailyGoalUpdates(${deviceLabel})")

  if (!requestingDevice) {
    logError("handleGetDailyGoalUpdates expected non-null deviceToCheck.")
    return
  }

  String fitBarkDogSlug = requestingDevice.getDeviceNetworkId()

  // 1. Construct the parameters for the HTTP GET call to request the daily goal details.
  Map getDailyGoalsHTTPParams = [
      uri    : "https://app.fitbark.com/api/v2/daily_goal/" + fitBarkDogSlug,
      headers: ["Authorization": "Bearer " + state.fitBarkAccessToken]
  ]

  // 4. Send the HTTP GET request.
  logDebug("Sending a GET request to fetch Daily Goals for ${deviceLabel} (${fitBarkDogSlug}).")
  try {
    httpGet(getDailyGoalsHTTPParams) { response ->
      Object responseJSON = response.getData()
      logInfo("Successfully fetched the Daily Goals for ${deviceLabel}: ${responseJSON}.")
      setScheduledDailyGoalsAttributeOnDevice(requestingDevice, responseJSON)
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Fetch ${deviceLabel} Daily Goals request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
  }
}

/**
 * Handles getting stats for similar dogs.
 *
 * @param deviceToUpdate The device requesting the information.
 */
void handleGetSimilarDogStats(DeviceWrapper requestingDevice) {
  String deviceLabel = requestingDevice?.getLabel()
  logTrace("handleGetSimilarDogStats(${deviceLabel})")

  if (!requestingDevice) {
    logError("handleGetSimilarDogStats expected non-null deviceToCheck.")
    return
  }

  String fitBarkDeviceSlug = requestingDevice.getDeviceNetworkId()

  // 1. Construct the parameters for the HTTP POST call to request stats for similar dogs.
  Map getSimilarDogStatsHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/similar_dogs_stats",
      headers: [ "Authorization" : "Bearer " + state.fitBarkAccessToken ],
      query: [ slug: fitBarkDeviceSlug ]
  ]

  // 2. Send the HTTP POST request.
  Object similarDogsStatsResponse
  try {
    logDebug("Sending a GET request to fetch Similar Dog Stats for ${deviceLabel}.")
    httpPost(getSimilarDogStatsHTTPParams) { response ->
      logTrace("getSimilarDogStats HTTP POST response: ${response}")
      similarDogsStatsResponse = response.getData().similar_dogs_stats
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("${deviceLabel} Similar Dog Stats request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
  }

  if (!similarDogsStatsResponse) {
    logError("Missing expected |similar_dogs_stats| value in response.")
    return
  }

  logInfo("Received Similar Dog Stats Response: ${similarDogsStatsResponse}.")

  Integer avgDailyActivity = similarDogsStatsResponse.this_average_daily_activity.toInteger()
  Integer avgDailyRestMinutes = similarDogsStatsResponse.this_average_daily_rest_minutes.toInteger()

  requestingDevice.sendEvent([name: "similarDogsAverageDailySteps", value: avgDailyActivity, unit: "BarkPoints"])
  requestingDevice.sendEvent([name: "similarDogsAverageDailyRestMinutes", value: avgDailyRestMinutes, unit: "BarkPoints"])
}

/**
 * Handles a device request to update its Daily Goal.
 *
 * @param deviceToUpdate The device requesting the update.
 * @param newDailyGoal A positive number describing the new daily BarkPoints goal.
 * @param newGoalStartDate A future date (tomorrow or later) at which point the new goal will become active.
 */
void handleUpdateDailyGoal(DeviceWrapper deviceToUpdate, Integer newDailyGoal, Date newGoalStartDate) {
  logTrace("handleUpdateDailyGoal(newDailyGoal: ${newDailyGoal}, newGoalStartDate: ${newGoalStartDate})")

  if (!deviceToUpdate) {
    logError("handleUpdateDailyGoal expected non-null childDeviceToUpdate.")
    return
  }

  String fitBarkDeviceSlug = deviceToUpdate.getDeviceNetworkId()
  logInfo("Configuring ${deviceToUpdate.getLabel()} Daily Goal. Slug: ${fitBarkDeviceSlug}.")

  // 1. Construct the parameters for the HTTP PUT call to request the access token.
  Map updateDailyGoalHTTPParams = [
      uri: "https://app.fitbark.com/api/v2/daily_goal/" + fitBarkDeviceSlug,
      headers: [ "Authorization" : "Bearer " + state.fitBarkAccessToken ],
      query: [
          daily_goal: newDailyGoal,
          date      : newGoalStartDate.format("yyyy-MM-dd")
      ]
  ]
  logDebug("Sending an HTTP PUT request to update Daily Goal with: ${updateDailyGoalHTTPParams}.")

  // 2. Send the HTTP PUT request.
  try {
    httpPut(updateDailyGoalHTTPParams) { response ->
      logTrace("updateDailyGoal HTTP PUT response: ${response}")

      Object responseJSON = response.getData()
      logInfo("Successfully configured future Daily Goal change: ${responseJSON.daily_goals}.")
      setScheduledDailyGoalsAttributeOnDevice(deviceToUpdate, responseJSON)
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    logError("Update Daily Goal request failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
  }
}

/**
 * Updates the 'Scheduled Daily Goals' attribute of the given device.
 *
 * @param deviceToUpdate The device whose attribute should be updated.
 * @param dailyGoalsJSONResponse The JSON response with a |daily_goals| parameter.
 */
void setScheduledDailyGoalsAttributeOnDevice(DeviceWrapper deviceToUpdate, Object dailyGoalsJSONResponse) {
  logTrace("setScheduledDailyGoalsAttributeOnDevice(dailyGoalsResponse: ${dailyGoalsJSONResponse})")

  if (!deviceToUpdate) {
    logError("setScheduledDailyGoalsAttributeOnDevice must have a non-null device to update.")
    return
  }

  if (!dailyGoalsJSONResponse.daily_goals) {
    logError("setScheduledDailyGoalsAttributeOnDevice must have |daily_goals| in its JSON input.")
    return
  }

  groovy.json.JsonBuilder dailyGoalsJSON = new groovy.json.JsonBuilder(dailyGoalsJSONResponse.daily_goals)
  logDebug("ScheduledDailyGoals JSON: ${dailyGoalsJSON}.")

  deviceToUpdate.sendEvent([ name: "scheduledDailyGoalUpdates", value: dailyGoalsJSON ])
}

/** ------------------------- **/
/** -------- HELPERS -------- **/
/** ------------------------- **/

/**
 * Returns whether the user has not yet input and saved their FitBark Client Credentials.
 *
 * @return A boolean indicating whether FitBark Client Credentials are not yet stored.
 */
boolean isMissingUserInputFitBarkClientCredentials() {
  logTraceHelper("isMissingUserInputFitBarkClientCredentials()")

  boolean hasInputFitBarkClientID = fitBarkClientID?.trim()
  boolean hasInputFitBarkClientSecret = fitBarkClientSecret?.trim()

  logTrace("Has FitBark Client ID: ${hasInputFitBarkClientID}, Client Secret: ${hasInputFitBarkClientSecret}.")

  return !hasInputFitBarkClientID || !hasInputFitBarkClientSecret
}

/**
 * Returns whether the user has successfully authorized their FitBark account.
 *
 * @return A boolean indicating whether the app is logged in to the user's FitBark account.
 */
boolean hasFitBarkAccessToken() {
  logTraceHelper("hasFitBarkAccessToken()")

  boolean hasAccessToken = state.fitBarkAccessToken?.trim()

  logTrace("Has FitBark Access Token: ${hasAccessToken}.")

  return hasAccessToken
}

/**
 * Returns whether the app currently has any linked FitBark devices.
 *
 * @return A boolean indicating whether there are linked devices.
 */
boolean hasLinkedFitBarkDogMonitors() {
  logTraceHelper("hasLinkedFitBarkDogMonitors()")

  List<ChildDeviceWrapper> linkedFitBarkDogMonitors = getChildDevices()

  boolean hasLinkedMonitors = !linkedFitBarkDogMonitors.isEmpty()
  logTrace("hasLinkedFitBarkDogMonitors() ? ${hasLinkedMonitors}")

  return hasLinkedMonitors
}

/**
 * Returns a map of sorted FitBark Devices that are currently linked.
 *
 * @return A map with lists of any linked FitBark Devices.
 */
Map getLinkedFitBarkDevices() {
  logTraceHelper("getLinkedFitBarkDevices()")

  List<ChildDeviceWrapper> allLinkedFitBarkDevices = getChildDevices()

  List<ChildDeviceWrapper> ownedLinkedFitBarkDevices = allLinkedFitBarkDevices.findAll {
    it.currentValue("userRelationshipType") == "OWNER"
  }

  List<ChildDeviceWrapper> followedLinkedFitBarkDevices = allLinkedFitBarkDevices.findAll {
    it.currentValue("userRelationshipType") == "FRIEND"
  }

  return [
      all: allLinkedFitBarkDevices,
      owned: ownedLinkedFitBarkDevices,
      followed: followedLinkedFitBarkDevices
  ]
}

/**
 * Creates the URL to authorize the user's FitBark account using the input FitBark API Client Credentials.
 *
 * @return A string containing the authorization URL.
 */
String createFitBarkAuthorizationURL() {
  logTraceHelper("createFitBarkAuthorizationURL()")

  // Make sure |createAccessToken| has been called.
  if (!state?.accessToken) {
    logDebug("createFitBarkAuthorizationURL() [Creating Access Token]")
    createAccessToken()
  }

  // 1.Build the Fitbark Hubitat App URL to redirect to after OAuth.
  String urlPathToRedirectToAfterOAuth = getHubUID() +
      "/apps/" + app.id +
      "/authentication?" +
      "access_token=" + state.accessToken
  logDebug("Creating Redirect URL: ${urlPathToRedirectToAfterOAuth} (|access_token| is for Hubitat, not FitBark).")

  // 2. Build the FitBark Authorization URL to link in the section.
  String fitBarkAuthorizationURL = "https://app.fitbark.com/oauth/authorize?" +
      "response_type=code" +
      "&client_id=" + fitBarkClientID +
      "&redirect_uri=" + HUBITAT_STATE_REDIRECT_URL +
      "&state=" + urlPathToRedirectToAfterOAuth
  logDebug("Created FitBark Authorization Link URL: ${fitBarkAuthorizationURL}.")

  return fitBarkAuthorizationURL
}

/**
 * Clears the values stored in the App State associated with the signed-in FitBark account.
 */
void clearStoredFitBarkAccountAuthValues() {
  logTraceHelper("storeFitBarkAccessTokenFromResponseJSON()")

  state.fitBarkAccessToken = null
  state.fitBarkRefreshToken = null
  state.fitBarkAccessTokenExpirationTimestamp = null
  state.signedInAccountInfo = null
}

/**
 * Stores the FitBark Access Token (if present in the given Token Request response JSON) in the App State.
 *
 * If not present, any stored FitBark Access Token or FitBark Refresh Token values are cleared.
 *
 * @param responseJSON The response JSON containing the FitBark |access_token|.
 */
void storeFitBarkAccessTokenFromResponseJSON(Object responseJSON) {
  logTraceHelper("storeFitBarkAccessTokenFromResponseJSON()")

  String accessToken = responseJSON.access_token
  if (!accessToken) {
    logError("Missing expected FitBark Access Token in: ${responseJSON}. Clearing any stored token state.")
    clearStoredFitBarkAccountAuthValues()
    return
  }
  logInfo("Successfully fetched a new FitBark Access Token:  ${makePartiallyCensoredString(accessToken)}.")

  // Store the updated FitBark Access Token in the App State.
  state.fitBarkAccessToken = accessToken.trim()

  // Check whether the response includes the expiration interval for the given Access Token, to indicate how long it
  // will be until the Access Token becomes stale/expired.
  if (!responseJSON.expires_in || !responseJSON.expires_in.toString().isInteger()) {
    logWarn("Unknown expiration interval for the new FitBark Access Token.")
    return
  }

  Integer tokenExpirationInSeconds = responseJSON.expires_in.toInteger()
  use(groovy.time.TimeCategory) {
    Date expirationDate = tokenExpirationInSeconds.seconds.from.now
    logDebug("FitBark Access Token expires on ${expirationDate}.")

    state.fitBarkAccessTokenExpirationTimestamp = expirationDate.getTime()
  }
}

/**
 * Tries to store the FitBark Refresh Token in the App State – if present in the given Token Request response JSON.
 *
 * @param responseJSON The response JSON containing the FitBark |access_token|.
 */
boolean maybeStoreFitBarkRefreshTokenFromResponseJSON(Object responseJSON) {
  logTraceHelper("maybeStoreFitBarkRefreshTokenFromResponseJSON()")

  String refreshToken = responseJSON.refresh_token
  if (!refreshToken) {
    logInfo("Did not receive FitBark Refresh Token in response: ${responseJSON}.")
    state.fitBarkRefreshToken = null
    return
  }
  logInfo("Successfully fetched a new FitBark Refresh Token: ${makePartiallyCensoredString(refreshToken)}.")

  // Store the updated FitBark Refresh Token in the App State.
  state.fitBarkRefreshToken = refreshToken.trim()
}

/**
 * Returns a partially censored string, with the first 3 and the last 3 characters still showing.
 *
 * @param inputStr The string to censor.
 * @return A partial censor of the input string.
 */
String makePartiallyCensoredString(String inputString) {
  if (!inputString) {
    return "[NULL]"
  }

  String censoredString = inputString
  censoredString = censoredString.replaceAll(".", "•")
  return inputString[0..1] + censoredString[2..-3] + inputString[-2..-1]
}

/**
 * Creates a styled button that links to the provided URL.
 *
 * @param linkURL The URL to link to.
 * @param buttonTitle The text displayed on the button.
 * @return A paragraph object with the button.
 */
Object createLinkButton(String linkURL, String buttonTitle) {
  String buttonStyle = "background-color: #0e9c9c; " +
      "color: white; " +
      "padding: 10px 16px; " +
      "margin-bottom: 20px; "
  "border-radius: 4px; " +
      "font-size: 16px"

  Object goToPageButton = paragraph "<a href='${linkURL}'>" +
      "<button type='button' style='${buttonStyle}'>" +
      "<b>${buttonTitle}</b>" +
      "</button>" +
      "</a>"

  return goToPageButton
}

/**
 * Creates a button that will link to another page in the app.
 *
 * @param pageName The name of the page to link to.
 * @param buttonTitle The text displayed on the button.
 * @return A paragraph object with the button.
 */
Object createGoToPageButton(pageName, buttonTitle) {
  String pageLink = "/installedapp/configure/${app.id}/${pageName}"
  return createLinkButton(pageLink, buttonTitle)
}

/**
 * Creates a header section with the given title.
 *
 * @param
 * @return A map defining a filler section.
 */
Map createHeaderSection(String title) {
  return section {
    createDividerParagraph()
    paragraph "<div style='background-color: #E6FFFF; text-align: center; font-size: 200%;'>${title}</div>"
    createDividerParagraph()
  }
}

/**
 * Creates a filler paragraph to add visual padding to a section.
 *
 * @return An Object defining a filler paragraph.
 */
Object createFillerParagraph() {
  Object fillerParagraph = paragraph """
  """
  return fillerParagraph
}

/**
 * Creates a divider section to add visual separation to a page.
 *
 * @return A map defining a divider section.
 */
Map createDividerSection() {
  return section {
    createDividerParagraph()
  }
}

/**
 * Creates a divider paragraph to add visual separation in a section.
 *
 * @return An Object defining a divider paragraph.
 */
Object createDividerParagraph() {
  Object dividerParagraph = paragraph "<div style='text-align: center; color: #66CCCC'>" +
      "⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺" +
      "⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺⸺" + "</div>"

  return dividerParagraph
}

/**
 * Creates a description paragraph with a smaller font size.
 *
 * @param descriptionText The text to display in the paragraph.
 * @return An Object defining the paragraph.
 */
Object createDescriptionParagraph(String descriptionText) {
  Object descriptionParagraph = paragraph "<font size='-1'>${descriptionText}</font>"

  return descriptionParagraph
}

/** --------------------------------------- **/
/** -------- BUTTON PRESS HANDLING -------- **/
/** --------------------------------------- **/

/**
 * Handler called when one of the app's buttons is pressed.
 *
 * @param btn The |name| of the button that was pressed.
 */
void appButtonHandler(String btn) {
  logTrace("appButtonHandler(${btn})")

  logInfo("Handling Button Press: ${btn}.")

  switch (btn) {

  // === Redirect URI Configuration === //

    case "validateRedirectURIConfiguration":
      requestFitBarkAPIClientAccessToken()
      break
    case "overwriteFitBarkRedirectURIConfiguration":
      // Overwrite any existing values in the Redirect URI configuration with just the Hubitat Redirect URL. This can
      // help solve any bugs with the JSON parsing and list splitting/joining that might occur when configuring multiple
      // Redirect URIs.
      updateClientRedirectURIs(state.fitBarkAPIClientAccessToken, HUBITAT_STATE_REDIRECT_URL)
      state.hasValidHubitatRedirectURI = false
      break

  // === Authorization Debug Actions === //

    case "forceRefreshFitBarkAccessToken":
      refreshFitBarkAccessToken()
      break
    case "signOutFitBark":
      signOutFitBarkAuthorization()
      break
    case "signOutAndDeleteFitBarkAPIClientCredentials":
      signOutFitBarkAuthorization()
      deleteFitBarkAPIClientCredentials()
      break
    case "deleteFitBarkAPIClientCredentials":
      deleteFitBarkAPIClientCredentials()
      break

  // === Device Debug Actions === //

    case "refreshAllLinkedDevicesButton":
      refreshAllLinkedFitBarkDogMonitors()
      break
    case "deleteAllLinkedDevices":
      deleteAllLinkedFitBarkDogMonitors()
      break

  // === Buttons with Links === //

    case "deviceDiscoveryPageButton":
      // No-op.
      break
    case "openDebugPageButton":
      // No-op.
      break

  // === Default Handler === //

    default:
      logError("Unknown Button Name passed to handler: ${btn}.")
      break
  }
}

/** ----------------------- **/
/** -------- DEBUG -------- **/
/** ----------------------- **/

/**
 * Creates the section linking to the Debug Page.
 *
 * @return A map defining the Debug Page Link Section.
 */
Map createDebugPageLinkSection() {
  logTrace("createDebugPageLinkSection()")

  if (isMissingUserInputFitBarkClientCredentials()) {
    return [:]
  }

  return section{
    createDescriptionParagraph(
        "Click the button below for debug actions, logging configuration and your FitBark access credentials.")

    createGoToPageButton("debugPage", "Go to App Debugging Tools")
  }
}

/**
 * Creates the Debug Page configuration for the FitBark Hubitat App.
 *
 * @return A map defining the Debug Page.
 */
Map debugPage() {
  logTrace("debugPage()")

  logDebug("######## Loading the App Debug Page. ########")

  return dynamicPage(name: "debugPage", uninstall: false) {
    createHeaderSection("FitBark App Debug Tools")

    section {
      paragraph "For more information, see the <a href='https://www.fitbark.com/dev/'>FitBark API Documentation</a>."
    }

    createDividerSection()
    createLogLevelDisplaySection()
    createLogLevelSettingsSection()

    if (!isMissingUserInputFitBarkClientCredentials() || hasFitBarkAccessToken()) {
      createDividerSection()
      createFitBarkClientCredentialDebugSection()
      createAccessTokenDebugSection()
    }

    if (hasFitBarkAccessToken() && hasLinkedFitBarkDogMonitors()) {
      createDividerSection()
      createDeviceDebugActionButtonsSection()
    }

    if (hasFitBarkAccessToken()) {
      createDividerSection()
      createSignOutButtonSection()
    }

    createDividerSection()

    section {
      createGoToPageButton("mainPage", "Done")
    }
  }
}

/**
 * Creates the debug section for FitBark API Client Credentials.
 *
 * @return A map defining the FitBark API Client Credentials Debug Section.
 */
Map createFitBarkClientCredentialDebugSection() {
  logTrace("createFitBarkClientCredentialDebugSection()")

  // Don't show the FitBark API Client Credentials Debug Section if there are no credentials to debug.
  if (isMissingUserInputFitBarkClientCredentials()) {
    return [:]
  }

  // Only display the unencrypted Client Secret in plaintext if the user enables it.
  String fitBarkClientSecretDisplayString = makePartiallyCensoredString(fitBarkClientSecret)
  if (settings?.showFitBarkClientSecret) {
    fitBarkClientSecretDisplayString = fitBarkClientSecret
  }

  // Always reset the setting to hide the secret by default.
  app.updateSetting("showFitBarkClientSecret", false)

  return section(hideable: true, hidden: true, "FitBark Developer API Client Credentials") {
    paragraph """
        • FitBark Client ID: '${fitBarkClientID}'
        • FitBark Client Secret: '${fitBarkClientSecretDisplayString}'
      """

    input(
        name: "showFitBarkClientSecret",
        type: "bool",
        title: "<i>Show uncensored FitBark Client Secret?</i>",
        defaultValue: false,
        submitOnChange: true
    )

    createDividerParagraph()
    input(
        name: "signOutAndDeleteFitBarkAPIClientCredentials",
        type: "button",
        title: "Reset FitBark Authorization",
        submitOnChange: true
    )
    paragraph "<p>Click the button above to reset the stored FitBark API Client Credentials.</p>" +
        "<p><b>⚠️ WARNING: This will log out and delete any authorized account or FitBark devices. ⚠️</b></p>"
  }
}

/**
 * Creates the section for debug actions for the FitBark Access and Refresh Tokens.
 *
 * @return A map defining the FitBark Token Debug Section.
 */
Map createAccessTokenDebugSection() {
  logTrace("createAccessTokenDebugSection()")

  // Don't show FitBark Token Debug Section until there is a FitBark Access Token to debug.
  if (!hasFitBarkAccessToken()) {
    return [:]
  }

  // Only display the plaintext FitBark Access Token if the user enables it.
  String fitBarkAccessTokenDisplayString = makePartiallyCensoredString(state.fitBarkAccessToken)
  if (settings?.showFitBarkAccessToken) {
    fitBarkAccessTokenDisplayString = state.fitBarkAccessToken
  }

  // Only display the plaintext FitBark Refresh Token if the user enables it.
  String fitBarkRefreshTokenDisplayString = makePartiallyCensoredString(state.fitBarkRefreshToken)
  if (settings?.showFitBarkRefreshToken && state.fitBarkRefreshToken) {
    fitBarkRefreshTokenDisplayString = state.fitBarkRefreshToken
  }

  String accessTokenExpirationDateStr
  use(groovy.time.TimeCategory) {
    Date expirationDate = new Date(state.fitBarkAccessTokenExpirationTimestamp)
    accessTokenExpirationDateStr = expirationDate.toString()
  }

  return section(hideable: true, hidden: true, "FitBark Access and Refresh Tokens") {
    paragraph "Your stored FitBark Access Token is: ${fitBarkAccessTokenDisplayString}."
    paragraph "It will expire on ${accessTokenExpirationDateStr}."
    input(
        name: "showFitBarkAccessToken",
        type: "bool",
        title: "<i>Show your stored FitBark Access Token in plaintext?</i>",
        defaultValue: false,
        submitOnChange: true
    )

    createDividerParagraph()


    if (state.fitBarkRefreshToken) {
      paragraph "Your stored FitBark Refresh Token is: ${fitBarkRefreshTokenDisplayString}."
      input(
          name: "showFitBarkRefreshToken",
          type: "bool",
          title: "<i>Show your stored FitBark Refresh Token in plaintext?</i>",
          defaultValue: false,
          submitOnChange: true
      )

      createDividerParagraph()

      input(
          name: "forceRefreshFitBarkAccessToken",
          type: "button",
          title: "Request FitBark Access Token Refresh",
          description: "Refresh the FitBark Access Token after its expiration.",
          submitOnChange: true
      )
      paragraph "<p>Your FitBark Access Token will expire one year after authorization. Click this button to refresh " +
          "your Access Token.</p>"
    } else {
      paragraph "You do not have a FitBark Refresh Token. ☹️"

      createDividerParagraph()

      paragraph """<p>
Your FitBark Access Token will expire one year after authorization. Click this button to re-authorize your FitBark 
account and update your Access Token.</p>
<p><b>⚠️ WARNING: Make sure you are not currently signed in to a different FitBark account on your browser.</b></p>"""

      // TODO: This needs to make sure it's not re-authorizing with a different user's account.
      // After getting the Access Token back, it should be used to check whether the username matches.
      //
      // Better TODO: Figure out why there's no Refresh Token being sent back from FitBark.

      createLinkButton(createFitBarkAuthorizationURL(), "Re-Authorize FitBark")
    }
  }
}

/**
 * Creates the section for buttons that can trigger Device Debug Actions.
 *
 * @return A map defining the Device Debug Actions Section.
 */
Map createDeviceDebugActionButtonsSection() {
  logTrace("createDeviceDebugActionButtonsSection()")

  // Don't show Device Debug Section until the user has authorized their account.
  if (!hasFitBarkAccessToken() || !hasLinkedFitBarkDogMonitors()) {
    return [:]
  }

  return section(hideable: true, hidden: true, "Device Debug Actions") {
    paragraph ""
    input(
        name: "refreshAllLinkedDevicesButton",
        type: "button",
        title: "Refresh All Linked Devices",
        submitOnChange: true
    )
    paragraph "<p>This will manually refresh all FitBark devices linked to this App.</p>"

    createDividerParagraph()
    input(
        name: "deleteAllLinkedDevices",
        type: "button",
        title: "Delete All Linked Devices",
        submitOnChange: true
    )
    paragraph "<p>This will delete any FitBark devices that you've discovered in Hubitat, but will not log you out.</p>"
  }
}

/**
 * Creates the section for a Sign-Out button.
 *
 * @return A map defining the Sign-Out Button Section.
 */
Map createSignOutButtonSection() {
  logTrace("createSignOutButtonSection()")

  // Don't show Sign-Out Section until the user has authorized their account.
  if (!hasFitBarkAccessToken()) {
    return [:]
  }

  return section {
    createFillerParagraph()
    input(
        name: "signOutFitBark",
        type: "button",
        title: "Log Out Current FitBark Account",
        submitOnChange: true
    )
    paragraph "<i>Note: Logging out will also remove and delete any connected FitBark devices.</i>"
  }
}

/** ------------------------- **/
/** -------- LOGGING -------- **/
/** ------------------------- **/

/**
 * Creates the section for surfacing the current Logging Settings to the user.
 *
 * @return A map defining the Logging Settings Display Section.
 */
Map createLogLevelDisplaySection() {
  logTrace("createLogLevelDisplaySection()")

  String logLevelIsEnabled = "✅"
  String logLevelIsDisabled = "❌"

  StringBuilder logLevelHTMLStrBuilder = new StringBuilder()
  logLevelHTMLStrBuilder << "<div style='border-style: double; border-color: #66CCCC; text-align: center'>"
  logLevelHTMLStrBuilder << "<div style='font-size: 90%'>"
  logLevelHTMLStrBuilder << "<br>"
  logLevelHTMLStrBuilder << "<b>Current Log Level Configuration</b>"
  logLevelHTMLStrBuilder << "</div>"
  logLevelHTMLStrBuilder << "<br>"
  logLevelHTMLStrBuilder << "<table style='width: 100%; font-size: 120%'>"
  logLevelHTMLStrBuilder << "<tr>"
  logLevelHTMLStrBuilder << "<td>ERROR</td>"
  logLevelHTMLStrBuilder << "<td>WARNING</td>"
  logLevelHTMLStrBuilder << "<td>INFO</td>"
  logLevelHTMLStrBuilder << "<td>DEBUG</td>"
  logLevelHTMLStrBuilder << "<td>TRACE</td>"
  logLevelHTMLStrBuilder << "</tr>"
  logLevelHTMLStrBuilder << "<tr style='font-size: 140%'>"
  logLevelHTMLStrBuilder << "<td>${settings?.logOutputEnableError ? logLevelIsEnabled : logLevelIsDisabled}</td>"
  logLevelHTMLStrBuilder << "<td>${settings?.logOutputEnableWarningLevel ? logLevelIsEnabled : logLevelIsDisabled}</td>"
  logLevelHTMLStrBuilder << "<td>${settings?.logOutputEnableInfo ? logLevelIsEnabled : logLevelIsDisabled}</td>"
  logLevelHTMLStrBuilder << "<td>${settings?.logOutputEnableDebug ? logLevelIsEnabled : logLevelIsDisabled}</td>"
  logLevelHTMLStrBuilder << "<td>${settings?.logOutputEnableTrace ? logLevelIsEnabled : logLevelIsDisabled}</td>"
  logLevelHTMLStrBuilder << "</tr>"
  logLevelHTMLStrBuilder << "</table>"
  logLevelHTMLStrBuilder << "<br>"
  logLevelHTMLStrBuilder << "</div>"
  String logLevelHTMLStr = logLevelHTMLStrBuilder.toString()

  return section {
    paragraph logLevelHTMLStr
  }
}

/**
 * Creates the section for configuring Logging Settings.
 *
 * @return A map defining the Logging Settings Section.
 */
Map createLogLevelSettingsSection() {
  logTrace("createLogLevelSettingsSection()")

  return section(hideable: true, hidden: true, "Log Level Configuration Settings") {
    input name: "logOutputEnableError", type: "bool", title: "Enable ERROR Logging?", defaultValue: true, submitOnChange: true
    input name: "logOutputEnableWarning", type: "bool", title: "Enable WARNING Logging?", defaultValue: true, submitOnChange: true
    input name: "logOutputEnableInfo", type: "bool", title: "Enable INFO Logging?", defaultValue: true, submitOnChange: true
    input name: "logOutputEnableDebug", type: "bool", title: "Enable DEBUG Logging?", defaultValue: false, submitOnChange: true
    input name: "logOutputEnableTrace", type: "bool", title: "Enable TRACE Logging?", defaultValue: false, submitOnChange: true
  }
}

/**
 * Logs a message at the ERROR level, if the Error Log Output setting is enabled.
 *
 * @param msg The message to log.
 */
private void logError(String msg) {
  if (settings?.logOutputEnableError) {
    log.error msg
  }
}

/**
 * Logs a message at the WARNING level, if the Warning Log Output setting is enabled.
 *
 * @param msg The message to log.
 */
private void logWarn(String msg) {
  if (settings?.logOutputEnableWarning) {
    log.warn msg
  }
}

/**
 * Logs a message at the INFO level, if the Info Log Output setting is enabled.
 *
 * @param msg The message to log.
 */
private void logInfo(String msg) {
  if (settings?.logOutputEnableInfo) {
    log.info msg
  }
}

/**
 * Logs a message at the DEBUG level, if the Debug Log Output setting is enabled.
 *
 * @param msg The message to log.
 */
private void logDebug(String msg) {
  if (settings?.logOutputEnableDebug) {
    log.debug msg
  }
}

/**
 * Logs a message at the TRACE level, if the Trace Log Output setting is enabled.
 *
 * @param msg The message to log.
 */
private void logTrace(String msg) {
  if (settings?.logOutputEnableTrace) {
    log.trace msg
  }
}

/**
 * Logs a message at the TRACE level for a helper method, if the Trace Helper Methods Log Output setting is enabled.
 *
 * @param msg The message to log.
 */
private void logTraceHelper(String msg) {
  // Change this value to true to enable logging helper methods.
  boolean logOutputEnableTraceHelpers = false
  if (logOutputEnableTraceHelpers) {
    log.trace msg
  }
}

/**
 * Logs the details from an HTTP Response exception using |logError|.
 *
 * @param msg An additional message to prefix to the log.
 * @param e The HTTP Response failure whose details should be appended to the log message.
 */
private void logHttpResponseException(String msg, groovyx.net.http.HttpResponseException e) {
  String exceptionMsg = "${e.getLocalizedMessage()}" +
      "(Status Code: ${e.getStatusCode()}) – " +
      "Response Data: ${e.response.data}" +
      "${e.getCause()}"
  logError(msg + " -- " + exceptionMsg)
}

/** --------------------------- **/
/** -------- CONSTANTS -------- **/
/** --------------------------- **/

/** Namespace used for the FitBark Hubitat App and FitBark Child Devices. */
@Field static String FITBARK_GROOVY_NAMESPACE = "midair.fitbark"

/** The name of the FitBark device type – as defined by the custom Driver code. */
@Field static String FITBARK_DRIVER_TYPE = "FitBark Dog Activity Monitor"

/** Redirect URL used for FitBark OAuth requests. See: https://docs.hubitat.com/index.php?title=App_OAuth. */
@Field static String HUBITAT_STATE_REDIRECT_URL = "https://cloud.hubitat.com/oauth/stateredirect"
