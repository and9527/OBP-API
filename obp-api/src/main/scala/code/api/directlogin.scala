/**
Open Bank Project - API
Copyright (C) 2011-2019, TESOBE GmbH

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE GmbH.
Osloer Strasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)

  */
package code.api

import java.util.Date

import code.api.util.APIUtil._
import code.api.util.ErrorMessages.{InvalidDirectLoginParameters, attemptedToOpenAnEmptyBox}
import code.api.util.NewStyle.HttpCode
import code.api.util._
import code.consumer.Consumers._
import code.model.dataAccess.AuthUser
import code.model.{Consumer, Token, TokenType, UserX}
import code.token.Tokens
import code.transaction.MappedTransaction
import code.util.Helper.{MdcLoggable, SILENCE_IS_GOLDEN}
import com.nimbusds.jwt.JWTClaimsSet
import com.openbankproject.commons.ExecutionContext.Implicits.global
import com.openbankproject.commons.model.User
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.rest.RestHelper
import net.liftweb.mapper.{By, By_>, Descending, OrderBy}
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.tryo

import scala.compat.Platform
import scala.concurrent.Future

/**
* This object provides the API calls necessary to
* authenticate users using JSON Web Tokens (http://jwt.io).
*/

object JSONFactory {
  case class TokenJSON( token : String )

  def stringOrNull(text: String) =
    if (text == null || text.isEmpty)
      null
    else
      text

  def stringOptionOrNull(text: Option[String]) =
    text match {
      case Some(t) => stringOrNull(t)
      case _ => null
    }

  def createTokenJSON(token: String): TokenJSON = {
    new TokenJSON(
      stringOrNull(token)
    )
  }
}

object DirectLogin extends RestHelper with MdcLoggable {

  // Our version of serve
  def dlServe(handler : PartialFunction[Req, JsonResponse]) : Unit = {
    val obpHandler : PartialFunction[Req, () => Box[LiftResponse]] = {
      new PartialFunction[Req, () => Box[LiftResponse]] {
        def apply(r : Req) = {
          handler(r)
        }
        def isDefinedAt(r : Req) = handler.isDefinedAt(r)
      }
    }
    super.serve(obpHandler)
  }

  dlServe
  {
    //Handling get request for a token
    case Req("my" :: "logins" :: "direct" :: Nil,_ , PostRequest) => {
      for{
        (httpCode: Int, message: String, userId:Long) <- createTokenFuture(getAllParameters)
        _ <- Future{grantEntitlementsToUseDynamicEndpointsInSpacesInDirectLogin(userId)}
      }   yield {
        if (httpCode == 200) {
          (JSONFactory.createTokenJSON(message), HttpCode.`201`(CallContext()))
        } else {
          unboxFullOrFail(Empty, None, message, httpCode)
        }
      }
    }
  }

  
  def grantEntitlementsToUseDynamicEndpointsInSpacesInDirectLogin(userId:Long) = {
    try {
      val resourceUser = UserX.findByResourceUserId(userId).openOrThrowException(s"$InvalidDirectLoginParameters can not find the resourceUser!")
      val authUser = AuthUser.findUserByUsernameLocally(resourceUser.name).openOrThrowException(s"$InvalidDirectLoginParameters can not find the auth user!")
      AuthUser.grantEntitlementsToUseDynamicEndpointsInSpaces(authUser)
      AuthUser.grantEmailDomainEntitlementsToUser(authUser)
      // User init actions
      AfterApiAuth.innerLoginUserInitAction(Full(authUser))
    } catch {
      case e: Throwable => // error handling, found wrong props value as early as possible.
        this.logger.error(s"directLogin.grantEntitlementsToUseDynamicEndpointsInSpacesInDirectLogin throw exception, details: $e" );
    }
  }
  /**
   * according username, password, consumer_key to generate a DirectLogin token
   * @param allParameters map {"username": "some_username", "password": "some_password", "consumer_key": "some_consumer_key"}
   * @return httpCode and token value
   */
  def createTokenFuture(allParameters: Map[String, String]): Future[(Int, String, Long)] = {
    val httpMethod = S.request match {
      case Full(r) => r.request.method
      case _ => "GET"
    }
    //Extract the directLogin parameters from the header and test if the request is valid
    for (
      (httpCode, message, directLoginParameters) <- validatorFuture("authorizationToken", httpMethod)
    ) yield {
      createTokenCommonPart(httpCode, message, directLoginParameters)
    }
  }
  /**
   * according username, password, consumer_key to generate a DirectLogin token
   * @param allParameters map {"username": "some_username", "password": "some_password", "consumer_key": "some_consumer_key"}
   * @return httpCode and token value
   */
  def createToken(allParameters: Map[String, String]) = {
    //Extract the directLogin parameters from the header and test if the request is valid
    val (httpCode, message, directLoginParameters) = validator("authorizationToken", allParameters)
    
    createTokenCommonPart(httpCode, message, directLoginParameters)
  }

  def createTokenCommonPart(code: Int, msg: String, directLoginParameters: Map[String, String]): (Int, String, Long) = {
    var message = msg
    var httpCode = code
    val userId: Long = (for {id <- getUserId(directLoginParameters)} yield id).getOrElse(0)       
    if (httpCode == 200) {
      if (userId == 0) {
        message = ErrorMessages.InvalidLoginCredentials
        httpCode = 401
      } else if (userId == AuthUser.usernameLockedStateCode) {
        message = ErrorMessages.UsernameHasBeenLocked
        httpCode = 401
      } else {
        val jwtPayloadAsJson =
          """{
                 "":""
               }"""

        val jwtClaims: JWTClaimsSet = JWTClaimsSet.parse(jwtPayloadAsJson)
        val (token: String, secret: String) = generateTokenAndSecret(jwtClaims)

        //Save the token that we have generated
        if (saveAuthorizationToken(directLoginParameters, token, secret, userId)) {
          message = token
        } else {
          httpCode = 500
          message = "invalid"
        }
      }
    }
    (httpCode, message, userId)
  }

  def getHttpMethod = S.request match {
    case Full(s) => s.post_? match {
      case true => "POST"
      case _    => "ERROR"
    }
    case _ => "ERROR"
  }

  /**Validate user supplied Direct Login parameters before they are used further,
    * guard maximum length and content of strings (a-z, 0-9 etc.) */
  private def validDirectLoginParameters(parameters: Map[String, String]): Iterable[String] = {
    for (key <- parameters.keys) yield {
      val parameterValue = parameters.get(key).get
      key match {
        case "username" =>
          checkUsernameString(parameterValue)
        case "password" =>
          basicPasswordValidation(parameterValue)
        case "consumer_key" =>
          basicConsumerKeyValidation(parameterValue)
        case "token" =>
          checkMediumString(parameterValue)
        case _ => ErrorMessages.InvalidDirectLoginParameters
      }
    }
  }

  //@return the missing parameters depending of the request type
  private def missingDirectLoginParameters(parameters: Map[String, String], requestType: String): Set[String] = {
    requestType match {
      case "authorizationToken" =>
        ("username" :: "password" :: "consumer_key" :: List()).toSet diff parameters.keySet
      case "protectedResource" =>
        ("token" :: List()).toSet diff parameters.keySet
      case _ =>
        parameters.keySet
    }
  }
  //return a Map containing the directLogin parameters : prameter -> value
  private def getAllParameters: Map[String, String] = {
    def toMap(parametersList: String) = {
      //transform the string "directLogin_prameter="value""
      //to a tuple (directLogin_parameter,Decoded(value))
      def dynamicListExtract(input: String) = {
        val directLoginPossibleParameters =
          List(
            "consumer_key",
            "token",
            "username",
            "password"
          )
        if (input contains "=") {
          val split = input.split("=", 2)
          val parameterValue = split(1).replaceAll("^\"|\"$", "");
          //add only OAuth parameters and not empty
          if (directLoginPossibleParameters.contains(split(0)) && !parameterValue.isEmpty)
            Some(split(0), parameterValue) // return key , value
          else
            None
        }
        else
          None
      }
      //we delete the "DirectLogin" prefix and trim the white spaces that may exist in the string
      val cleanedParameterList = parametersList.stripPrefix("DirectLogin").split(",").map(_.trim()).toList
      val params = Map(cleanedParameterList.flatMap(dynamicListExtract _): _*)
      params
    }

    S.request match {
      // Recommended header style i.e. DirectLogin: username=s, password=s, consumer_key=s
      case Full(a) if a.header("DirectLogin").isDefined == true =>
        toMap(a.header("DirectLogin").openOrThrowException(attemptedToOpenAnEmptyBox + " => getAllParameters"))
      // Deprecated header style i.e. Authorization: DirectLogin username=s, password=s, consumer_key=s
      case Full(a) => a.header("Authorization") match {
        case Full(header) => {
          if (header.contains("DirectLogin"))
            toMap(header)
          else
            Map("error" -> "header incorrect")
        }
        case _ => Map("error" -> "missing header")
      }
      case _ => Map("error" -> "request incorrect")
    }
  }


  //Check if the request (access token or request token) is valid and return a tuple
  def validator(requestType : String, allParameters: Map[String, String] = getAllParameters) : (Int, String, Map[String,String]) = {

    def validAccessToken(tokenKey: String): Boolean = {
      Tokens.tokens.vend.getTokenByKeyAndType(tokenKey, TokenType.Access) match {
        case Full(token) => token.isValid match {
          case true =>
            // Only last issued token is considered as a valid one
            val isNotLastIssuedToken = Token.findAll(
              By(Token.userForeignKey, token.userForeignKey.get),
              By(Token.consumerId, token.consumerId.get),
              By_>(Token.expirationDate, token.expirationDate.get)
            ).size > 0
            if(isNotLastIssuedToken) false else true
          case false => false
        }
        case _ => false
      }
    }

    var message = ""
    var httpCode: Int = 500

    //are all the necessary directLogin parameters present?
    val missingParams = missingDirectLoginParameters(allParameters, requestType)
    //guard maximum length and content of strings (a-z, 0-9 etc.) for parameters
    val validParams = validDirectLoginParameters(allParameters)

    if (missingParams.nonEmpty) {
      message = ErrorMessages.DirectLoginMissingParameters + missingParams.mkString(", ")
      httpCode = 400
    }
    else if(SILENCE_IS_GOLDEN != validParams.mkString("")){
      message = validParams.mkString("")
      httpCode = 400
    }
    else if (
      requestType == "protectedResource" &&
        ! validAccessToken(allParameters.getOrElse("token", ""))
    ) {
      message = ErrorMessages.DirectLoginInvalidToken + allParameters.getOrElse("token", "")
      httpCode = 401
    }
    //check if the application is registered and active
    else if (
      requestType == "authorizationToken" &&
        APIUtil.getPropsAsBoolValue("direct_login_consumer_key_mandatory", true) &&
        ! APIUtil.registeredApplication(allParameters.getOrElse("consumer_key", ""))) {

      logger.error("application: " + allParameters.getOrElse("consumer_key", "") + " not found")
      message = ErrorMessages.InvalidConsumerKey
      httpCode = 401
    }
    else
      httpCode = 200
    if(message.nonEmpty)
      logger.error("error message : " + message)
    (httpCode, message, allParameters)
  }


  //Check if the request (access token or request token) is valid and return a tuple
  def validatorFuture(requestType : String, httpMethod : String) : Future[(Int, String, Map[String,String])] = {

    def validAccessTokenFuture(tokenKey: String) = {
      Tokens.tokens.vend.getTokenByKeyAndTypeFuture(tokenKey, TokenType.Access) map {
        case Full(token) => token.isValid match {
          case true => 
            // Only last issued token is considered as a valid one
            val isNotLastIssuedToken = Token.findAll(
              By(Token.userForeignKey, token.userForeignKey.get), 
              By(Token.consumerId, token.consumerId.get),
              By_>(Token.expirationDate, token.expirationDate.get)
            ).size > 0
            if(isNotLastIssuedToken) false else true
          case false => false
        }
        case _ => false
      }
    }

    var message = ""
    var httpCode: Int = 500

    val parameters = getAllParameters

    //are all the necessary directLogin parameters present?
    val missingParams = missingDirectLoginParameters(parameters, requestType)
    //guard maximum length and content of strings (a-z, 0-9 etc.) for parameters
    val validParams = validDirectLoginParameters(parameters)

    val validF =
      if (requestType == "protectedResource") {
        validAccessTokenFuture(parameters.getOrElse("token", ""))
      } else if (requestType == "authorizationToken" &&
                APIUtil.getPropsAsBoolValue("direct_login_consumer_key_mandatory", true))
      {
        APIUtil.registeredApplicationFuture(parameters.getOrElse("consumer_key", ""))
      } else {
        Future{true}
      }

    // Please note that after this point S.request for instance cannot be used directly
    // If you need it later assign it to some variable and pass it
    for {
      valid <- validF
    } yield {
      if (missingParams.nonEmpty) {
        message = ErrorMessages.DirectLoginMissingParameters + missingParams.mkString(", ")
        httpCode = 400
      }
      else if(SILENCE_IS_GOLDEN != validParams.mkString("")){
        message = validParams.mkString("")
        httpCode = 400
      }
      else if ( requestType == "protectedResource" &&
                !valid
      ) {
        message = ErrorMessages.DirectLoginInvalidToken + parameters.getOrElse("token", "")
        httpCode = 401
      }
      //check if the application is registered and active
      else if ( requestType == "authorizationToken" &&
                APIUtil.getPropsAsBoolValue("direct_login_consumer_key_mandatory", true) &&
                !valid)
      {
        logger.error("application: " + parameters.getOrElse("consumer_key", "") + " not found")
        message = ErrorMessages.InvalidConsumerKey
        httpCode = 401
      }
      else
        httpCode = 200
      if(message.nonEmpty)
        logger.error("error message : " + message)
      (httpCode, message, parameters)
    }

  }

  private def generateTokenAndSecret(claims: JWTClaimsSet): (String, String) =
  {
    // generate random string
    val secret_message = Helpers.randomString(40)
    // generate jwt token
    val token_message = CertificateUtil.jwtWithHmacProtection(claims, secret_message)
    (token_message, secret_message)
  }

  private def saveAuthorizationToken(directLoginParameters: Map[String, String], tokenKey: String, tokenSecret: String, userId: Long) =
  {
    import code.model.TokenType
    val consumerId = consumers.vend.getConsumerByConsumerKey(directLoginParameters.getOrElse("consumer_key", "")) match {
      case Full(consumer) => Some(consumer.id.get)
      case _ => None
    }
    val currentTime = Platform.currentTime
    val expiration = APIUtil.getPropsAsIntValue("token_expiration_weeks", 4)
    val tokenDuration : Long = Helpers.weeks(expiration)
    val tokenSaved = Tokens.tokens.vend.createToken(TokenType.Access,
                                                    consumerId,
                                                    Some(userId),
                                                    Some(tokenKey),
                                                    Some(tokenSecret),
                                                    Some(tokenDuration),
                                                    Some(new Date(currentTime+tokenDuration)),
                                                    Some(new Date(currentTime)),
                                                    None
                                                    )
    tokenSaved match {
      case Full(_) => true
      case _       => false
    }
  }

  def getUser : Box[User] = {
    val httpMethod = S.request match {
      case Full(r) => r.request.method
      case _ => "GET"
    }
    val (httpCode, message, directLoginParameters) = validator("protectedResource")

    if (httpCode == 400 || httpCode == 401)
      ParamFailure(message, Empty, Empty, APIFailure(message, httpCode))
    else {
      val user = for {
        u <- getUserFromToken(if (directLoginParameters.isDefinedAt("token")) directLoginParameters.get("token") else Empty)
      } yield u

      if (user.isEmpty)
        ParamFailure(message, Empty, Empty, APIFailure(message, httpCode))
      else
        user
    }
  }

  def getUserFromDirectLoginHeaderFuture(sc: CallContext) : Future[(Box[User], Option[CallContext])] = {
    val httpMethod = S.request match {
      case Full(r) => r.request.method
      case _ => "GET"
    }
    for {
      (httpCode, message, directLoginParameters) <- validatorFuture("protectedResource", httpMethod)
      _ <- Future { if (httpCode == 400 || httpCode == 401) Empty else Full("ok") } map { x => fullBoxOrException(x ?~! message) }
      consumer <- OAuthHandshake.getConsumerFromTokenFuture(200, (if (directLoginParameters.isDefinedAt("token")) directLoginParameters.get("token") else Empty))
      user <- OAuthHandshake.getUserFromTokenFuture(200, (if (directLoginParameters.isDefinedAt("token")) directLoginParameters.get("token") else Empty))
    } yield {
      (user, Some(sc.copy(user = user, directLoginParams = directLoginParameters, consumer = consumer)))
    }
  }

  private def getUserId(directLoginParameters: Map[String, String]): Box[Long] = {
    val username = directLoginParameters.getOrElse("username", "")
    val password = directLoginParameters.getOrElse("password", "")

    var userId = for {id <- AuthUser.getResourceUserId(username, password)} yield id

    if (userId.isEmpty) {
      if ( ! AuthUser.externalUserHelper(username, password).isEmpty)
      	userId = for {id <- AuthUser.getResourceUserId(username, password)} yield id
    }

    userId
  }


  def getUserFromToken(tokenID : Box[String]) : Box[User] = {
    logger.debug("DirectLogin header correct ")
    Tokens.tokens.vend.getTokenByKey(tokenID.getOrElse("")) match {
      case Full(token) => {
        logger.debug("access token: " + token + " found")
        val user = token.user
        //just a log
        user match {
          case Full(u) => logger.debug("user " + u.name + " was found from the DirectLogin token")
          case _ => logger.debug("no user was found for the DirectLogin token")
        }
        user
      }
      case _ => {
        logger.warn("no token " + tokenID.getOrElse("") + " found")
        Empty
      }
    }
  }

  def getConsumer: Box[Consumer] = {
    logger.debug("DirectLogin header correct ")
    val httpMethod = S.request match {
      case Full(r) => r.request.method
      case _ => "GET"
    }

    val (httpCode, message, directLoginParameters) = validator("protectedResource")

    val consumer: Option[Consumer] = for {
      tokenId: String <- directLoginParameters.get("token")
      token: Token <- Tokens.tokens.vend.getTokenByKey(tokenId)
      consumer: Consumer <- token.consumer
    } yield {
      consumer
    }
    consumer
  }

  def getConsumer(token: String): Box[Consumer] = {
    val consumer: Option[Consumer] = for {
      tokenId: String <- Full(token)
      token: Token <- Tokens.tokens.vend.getTokenByKey(tokenId)
      consumer: Consumer <- token.consumer
    } yield {
      consumer
    }
    consumer
  }
}
