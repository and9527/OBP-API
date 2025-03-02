package code.api.v2_0_0

import java.util.{Calendar, Date}

import code.api.Constant._
import code.TransactionTypes.TransactionType
import code.api.{APIFailure, APIFailureNewStyle}
import code.api.Constant._
import code.api.ResourceDocs1_4_0.SwaggerDefinitionsJSON._
import code.api.util.APIUtil._
import code.api.util.ApiTag._
import code.api.util.ErrorMessages.UserNotLoggedIn
import code.api.util.NewStyle.HttpCode
import code.api.util._
import code.api.v1_2_1.OBPAPI1_2_1._
import code.api.v1_2_1.{JSONFactory => JSONFactory121}
import code.api.v1_4_0.JSONFactory1_4_0
import code.api.v1_4_0.JSONFactory1_4_0.ChallengeAnswerJSON
import code.api.v2_0_0.JSONFactory200.{privateBankAccountsListToJson, _}
import code.bankconnectors.Connector
import code.customer.CustomerX
import code.entitlement.Entitlement
import code.fx.fx
import code.meetings.Meetings
import code.model._
import code.model.dataAccess.{AuthUser, BankAccountCreation}
import code.search.{elasticsearchMetrics, elasticsearchWarehouse}
import code.socialmedia.SocialMediaHandle
import code.usercustomerlinks.UserCustomerLink
import code.util.Helper
import code.util.Helper.booleanToBox
import code.views.Views
import com.openbankproject.commons.model._
import net.liftweb.common.{Full, _}
import net.liftweb.http.CurrentReq
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonAST.{JValue, prettyRender}
import net.liftweb.mapper.By
import net.liftweb.util.Helpers.tryo

import scala.collection.immutable.Nil
import scala.collection.mutable.ArrayBuffer
import com.openbankproject.commons.ExecutionContext.Implicits.global
import com.openbankproject.commons.util.ApiVersion

import scala.concurrent.Future
// Makes JValue assignment to Nil work
import code.api.util.ApiRole._
import code.api.util.ErrorMessages._
import code.api.v2_0_0.AccountsHelper._
import com.openbankproject.commons.model.{AmountOfMoneyJsonV121 => AmountOfMoneyJSON121}
import net.liftweb.json.Extraction

trait APIMethods200 {
  //needs to be a RestHelper to get access to JsonGet, JsonPost, etc.
  self: RestHelper =>

  // helper methods begin here
  private def privateBankAccountBasicListToJson(bankAccounts: List[BankAccount], privateViewsUserCanAccessAtOneBank : List[View]): JValue = {
    Extraction.decompose(privateBasicBankAccountList(bankAccounts, privateViewsUserCanAccessAtOneBank))
  }
  // shows a small representation of View
  private def publicBankAccountBasicListToJson(bankAccounts: List[BankAccount], publicViews : List[View]): JValue = {
    Extraction.decompose(publicBasicBankAccountList(bankAccounts, publicViews))
  }
  // shows a small representation of View
  private def publicBankAccountBasicList(bankAccounts: List[BankAccount], publicViews : List[View]): List[BasicAccountJSON] = {
    publicBasicBankAccountList(bankAccounts, publicViews)
  }
  
  // Shows accounts without view
  private def coreBankAccountListToJson(callerContext: CallerContext, codeContext: CodeContext, user: User, bankAccounts: List[BankAccount], privateViewsUserCanAccess : List[View]): JValue = {
    Extraction.decompose(coreBankAccountList(callerContext, codeContext, user, bankAccounts, privateViewsUserCanAccess))
  }

  private def privateBasicBankAccountList(bankAccounts: List[BankAccount], privateViewsUserCanAccessAtOneBank : List[View]): List[BasicAccountJSON] = {
    val accJson : List[BasicAccountJSON] = bankAccounts.map(account => {
      val viewsAvailable : List[BasicViewJson] =
        privateViewsUserCanAccessAtOneBank
          .filter(v =>v.bankId==account.bankId && v.accountId ==account.accountId && v.isPrivate)//filter the view for this account.
          .map(JSONFactory200.createBasicViewJSON(_))
          .distinct
      JSONFactory200.createBasicAccountJSON(account,viewsAvailable)
    })
    accJson
  }
  
  private def publicBasicBankAccountList(bankAccounts: List[BankAccount], publicViews: List[View]): List[BasicAccountJSON] = {
    val accJson : List[BasicAccountJSON] = bankAccounts.map(account => {
      val viewsAvailable : List[BasicViewJson] =
        publicViews
          .filter(v =>v.bankId==account.bankId && v.accountId ==account.accountId && v.isPublic)
          .map(v => JSONFactory200.createBasicViewJSON(v))
          .distinct
      JSONFactory200.createBasicAccountJSON(account,viewsAvailable)
    })
    accJson
  }

  private def coreBankAccountList(callerContext: CallerContext, codeContext: CodeContext, user: User, bankAccounts: List[BankAccount], privateViewsUserCanAccess : List[View]): List[CoreAccountJSON] = {
    val accJson : List[CoreAccountJSON] = bankAccounts.map(account => {
      val viewsAvailable : List[BasicViewJson] =
        privateViewsUserCanAccess
          .filter(v =>v.bankId==account.bankId && v.accountId ==account.accountId && v.isPrivate)//filter the view for this account.
          .map(JSONFactory200.createBasicViewJSON(_))
          .distinct

      val dataContext = DataContext(Full(user), Some(account.bankId), Some(account.accountId), Empty, Empty, Empty)

      val links = code.api.util.APIUtil.getHalLinks(callerContext, codeContext, dataContext)

      JSONFactory200.createCoreAccountJSON(account, links)
    })
    accJson
  }



  // helper methods end here

  val Implementations2_0_0 = new Object() {

    val resourceDocs = ArrayBuffer[ResourceDoc]()
    val apiRelations = ArrayBuffer[ApiRelation]()

    val emptyObjectJson = EmptyClassJson()
    val apiVersion = ApiVersion.v2_0_0 // was String "2_0_0"

    val codeContext = CodeContext(resourceDocs, apiRelations)





    resourceDocs += ResourceDoc(
      getPrivateAccountsAllBanks,
      apiVersion,
      "getPrivateAccountsAllBanks",
      "GET",
      "/accounts",
      "Get all Accounts at all Banks",
      s"""Get all accounts at all banks the User has access to.
         |Returns the list of accounts at that the user has access to at all banks.
         |For each account the API returns the account ID and the available views.
         |
         |${authenticationRequiredMessage(true)}
         |
         |""".stripMargin,
      emptyObjectJson,
      basicAccountsJSON,
      List(UserNotLoggedIn, UnknownError),
      List(apiTagAccount, apiTagPrivateData, apiTagPublicData))


    lazy val getPrivateAccountsAllBanks : OBPEndpoint = {
      //get accounts for all banks (private + public)
      case "accounts" :: Nil JsonGet req => {
        cc =>
          for {
            u <- cc.user ?~  UserNotLoggedIn
            (privateViewsUserCanAccess, privateAccountAccess) <- Full(Views.views.vend.privateViewsUserCanAccess(u))
            privateAccounts <- Full(BankAccountX.privateAccounts(privateAccountAccess))
          } yield {
            successJsonResponse(privateBankAccountsListToJson(privateAccounts, privateViewsUserCanAccess ))
          }
      }
    }

    resourceDocs += ResourceDoc(
      corePrivateAccountsAllBanks,
      apiVersion,
      "corePrivateAccountsAllBanks",
      "GET",
      "/my/accounts",
      "Get Accounts at all Banks (Private)",
      s"""Get private accounts at all banks (Authenticated access)
        |Returns the list of accounts containing private views for the user at all banks.
        |For each account the API returns the ID and the available views.
        |
        |${authenticationRequiredMessage(true)}
        |
        |""".stripMargin,
      emptyObjectJson,
      coreAccountsJSON,
      List(UnknownError),
      List(apiTagAccount, apiTagPrivateData, apiTagPsd2))


    apiRelations += ApiRelation(corePrivateAccountsAllBanks, getCoreAccountById, "detail")
    apiRelations += ApiRelation(corePrivateAccountsAllBanks, corePrivateAccountsAllBanks, "self")



        lazy val corePrivateAccountsAllBanks : OBPEndpoint = {
          //get private accounts for all banks
          case "my" :: "accounts" :: Nil JsonGet req => {
            cc =>
              for {
                u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
                (privateViewsUserCanAccess, privateAccountAccess) <- Full(Views.views.vend.privateViewsUserCanAccess(u))
                privateAccounts <- Full(BankAccountX.privateAccounts(privateAccountAccess))
              } yield {
                val coreBankAccountListJson = coreBankAccountListToJson(CallerContext(corePrivateAccountsAllBanks), codeContext, u, privateAccounts, privateViewsUserCanAccess)
                val response = successJsonResponse(coreBankAccountListJson)
                response
              }
          }
        }



    resourceDocs += ResourceDoc(
      publicAccountsAllBanks,
      apiVersion,
      "publicAccountsAllBanks",
      "GET",
      "/accounts/public",
      "Get Public Accounts at all Banks",
      s"""Get public accounts at all banks (Anonymous access).
        |Returns accounts that contain at least one public view (a view where is_public is true)
        |For each account the API returns the ID and the available views.
        |
        |${authenticationRequiredMessage(false)}
        |
        |""".stripMargin,
      emptyObjectJson,
      basicAccountsJSON,
      List(UserNotLoggedIn, CannotGetAccounts, UnknownError),
      List(apiTagAccountPublic, apiTagAccount, apiTagPublicData)
    )
    lazy val publicAccountsAllBanks : OBPEndpoint = {
      //get public accounts for all banks
      case "accounts" :: "public" :: Nil JsonGet req => {
        cc =>
          for {
            (publicViews, publicAccountAccess) <- Future(Views.views.vend.publicViews)
            publicAccountsJson <- NewStyle.function.tryons(CannotGetAccounts, 400, Some(cc)){
              publicBankAccountBasicList(BankAccountX.publicAccounts(publicAccountAccess), publicViews)
            }
          } yield {
            (BasicAccountsJSON(publicAccountsJson), HttpCode.`200`(cc))
          }
      }
    }




    resourceDocs += ResourceDoc(
      getPrivateAccountsAtOneBank,
      apiVersion,
      "getPrivateAccountsAtOneBank",
      "GET",
      "/banks/BANK_ID/accounts",
      "Get Accounts at Bank",
      s"""
        |Returns the list of accounts at BANK_ID that the user has access to.
        |For each account the API returns the account ID and the views available to the user..
        |Each account must have at least one private View.
        |
        |${authenticationRequiredMessage(true)}
      """.stripMargin,
      emptyObjectJson,
      basicAccountsJSON,
      List(BankNotFound, UnknownError),
      List(apiTagAccount, apiTagPrivateData, apiTagPublicData, apiTagNewStyle)
    )

    def processAccounts(privateViewsUserCanAccessAtOneBank: List[View], availablePrivateAccounts: List[BankAccount]) = {
      privateBankAccountBasicListToJson(availablePrivateAccounts, privateViewsUserCanAccessAtOneBank)
    }
    lazy val getPrivateAccountsAtOneBank : OBPEndpoint = {

      case "banks" :: BankId(bankId) :: "accounts" :: Nil JsonGet req => {
        cc =>
          for{
            (Full(u), callContext) <- authenticatedAccess(cc)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (privateViewsUserCanAccessAtOneBank, privateAccountAccess) = Views.views.vend.privateViewsUserCanAccessAtBank(u, bankId)
            (availablePrivateAccounts, callContext) <- bank.privateAccountsFuture(privateAccountAccess, callContext)
          } yield {
            (processAccounts(privateViewsUserCanAccessAtOneBank, availablePrivateAccounts), HttpCode.`200`(callContext))
          }
      }
    }

    def corePrivateAccountsAtOneBankResult (callerContext: CallerContext, codeContext: CodeContext,  user: User, privateAccounts: List[BankAccount], privateViewsUserCanAccess : List[View]) ={
      successJsonResponse(coreBankAccountListToJson(callerContext, codeContext,  user: User, privateAccounts, privateViewsUserCanAccess))
    }

    resourceDocs += ResourceDoc(
      corePrivateAccountsAtOneBank,
      apiVersion,
      "corePrivateAccountsAtOneBank",
      "GET",
      "/my/banks/BANK_ID/accounts",
      "Get Accounts at Bank (Private)",
      s"""Get private accounts at one bank (Authenticated access).
        |Returns the list of accounts containing private views for the user at BANK_ID.
        |For each account the API returns the ID and label. To also see the list of Views, see privateAccountsAtOneBank
        |
        |
        |This call MAY have an alias /bank/accounts but ONLY if defaultBank is set in Props
        |
        |${authenticationRequiredMessage(true)}
        |
        |""".stripMargin,
      emptyObjectJson,
      coreAccountsJSON,
      List(UserNotLoggedIn, UnknownError),
      List(apiTagAccount, apiTagPrivateData, apiTagPsd2, apiTagNewStyle, apiTagNewStyle))

    apiRelations += ApiRelation(corePrivateAccountsAtOneBank, createAccount, "new")
    apiRelations += ApiRelation(corePrivateAccountsAtOneBank, corePrivateAccountsAtOneBank, "self")


    // This contains an approach to surface a resource via different end points in case of a default bank.
    // The second path is experimental
    lazy val corePrivateAccountsAtOneBank : OBPEndpoint = {
      // get private accounts for a single bank
      case "my" :: "banks" :: BankId(bankId) :: "accounts" ::  Nil JsonGet req => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (privateViewsUserCanAccessAtOneBank, privateAccountAccess) = Views.views.vend.privateViewsUserCanAccessAtBank(u, bankId)
            (privateAccountsForOneBank, callContext) <- bank.privateAccountsFuture(privateAccountAccess, callContext)
          } yield {
            val result = corePrivateAccountsAtOneBankResult(CallerContext(corePrivateAccountsAtOneBank), codeContext, u, privateAccountsForOneBank, privateViewsUserCanAccessAtOneBank)
            (result, HttpCode.`200`(callContext))
          }
      }
      // Also we support accounts/private to maintain compatibility with 1.4.0
      case "my" :: "banks" :: BankId(bankId) :: "accounts" :: "private" :: Nil JsonGet req => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (privateViewsUserCanAccessAtOneBank, privateAccountAccess) = Views.views.vend.privateViewsUserCanAccessAtBank(u, bankId)
            (privateAccountsForOneBank, callContext) <- bank.privateAccountsFuture(privateAccountAccess, callContext)
          } yield {
           val result = corePrivateAccountsAtOneBankResult(CallerContext(corePrivateAccountsAtOneBank), codeContext, u, privateAccountsForOneBank, privateViewsUserCanAccessAtOneBank)
            (result, HttpCode.`200`(callContext))
          }
      }
      // Supports idea of default bank
      case "bank" :: "accounts" :: Nil JsonGet req => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            (bank, callContext) <- NewStyle.function.getBank(BankId(defaultBankId), callContext)
            (privateViewsUserCanAccessAtOneBank, privateAccountAccess) = Views.views.vend.privateViewsUserCanAccessAtBank(u, BankId(defaultBankId))
            (availablePrivateAccounts, callContext) <- bank.privateAccountsFuture(privateAccountAccess, callContext)
          } yield {
            val result = corePrivateAccountsAtOneBankResult(CallerContext(corePrivateAccountsAtOneBank), codeContext, u, availablePrivateAccounts, privateViewsUserCanAccessAtOneBank)
            (result, HttpCode.`200`(callContext))
          }
      }

    }


    resourceDocs += ResourceDoc(
      privateAccountsAtOneBank,
      apiVersion,
      "privateAccountsAtOneBank",
      "GET",
      "/banks/BANK_ID/accounts/private",
      "Get private accounts at one bank",
      s"""Returns the list of private accounts at BANK_ID that the user has access to.
        |For each account the API returns the ID and the available views.
        |
        |If you want to see more information on the Views, use the Account Detail call.
        |If you want less information about the account, use the /my accounts call
        |
        |
        |${authenticationRequiredMessage(true)}
        |
        |""".stripMargin,
      emptyObjectJson,
      basicAccountsJSON,
      List(UserNotLoggedIn, BankNotFound, UnknownError),
      List(apiTagAccount, apiTagNewStyle, apiTagPsd2)
    )

    lazy val privateAccountsAtOneBank : OBPEndpoint = {
      //get private accounts for a single bank
      case "banks" :: BankId(bankId) :: "accounts" :: "private" :: Nil JsonGet req => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (privateViewsUserCanAccessAtOneBank, privateAccountAccess) = Views.views.vend.privateViewsUserCanAccessAtBank(u, bankId)
            (availablePrivateAccounts, callContext) <- bank.privateAccountsFuture(privateAccountAccess, callContext)
          } yield {
            (privateBankAccountsListToJson(availablePrivateAccounts, privateViewsUserCanAccessAtOneBank), HttpCode.`200`(callContext))
          }
      }
    }






    resourceDocs += ResourceDoc(
      publicAccountsAtOneBank,
      apiVersion,
      "publicAccountsAtOneBank",
      "GET",
      "/banks/BANK_ID/accounts/public",
      "Get Public Accounts at Bank",
      s"""Returns a list of the public accounts (Anonymous access) at BANK_ID. For each account the API returns the ID and the available views.
        |
        |${authenticationRequiredMessage(false)}
        |
        |""".stripMargin,
      emptyObjectJson,
      basicAccountsJSON,
      List(UnknownError),
      List(apiTagAccountPublic, apiTagAccount, apiTagPublicData, apiTagNewStyle))

    lazy val publicAccountsAtOneBank : OBPEndpoint = {
      //get public accounts for a single bank
      case "banks" :: BankId(bankId) :: "accounts" :: "public" :: Nil JsonGet req => {
        cc =>
          for {
            (_, callContext) <- anonymousAccess(cc)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
          } yield {
            val (publicViewsForBank, publicAccountAccess) = Views.views.vend.publicViewsForBank(bank.bankId)
            val publicAccountsJson = publicBankAccountBasicListToJson(bank.publicAccounts(publicAccountAccess), publicViewsForBank)
            (publicAccountsJson, HttpCode.`200`(callContext))
          }
      }
    }

    resourceDocs += ResourceDoc(
      getKycDocuments,
      apiVersion,
      "getKycDocuments",
      "GET",
      "/customers/CUSTOMER_ID/kyc_documents",
      "Get Customer KYC Documents",
      s"""Get KYC (know your customer) documents for a customer specified by CUSTOMER_ID
        |Get a list of documents that affirm the identity of the customer
        |Passport, driving licence etc.
        |${authenticationRequiredMessage(false)}""".stripMargin,
      emptyObjectJson,
      kycDocumentsJSON,
      List(UserNotLoggedIn, CustomerNotFoundByCustomerId, UnknownError),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canGetAnyKycDocuments))
    )

    // TODO Add Role

    lazy val getKycDocuments  : OBPEndpoint = {
      case "customers" :: customerId :: "kyc_documents" :: Nil JsonGet _ => {
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement("", u.userId, ApiRole.canGetAnyKycDocuments, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            (kycDocuments, callContxt) <- NewStyle.function.getKycDocuments(customerId, callContext)
          } yield {
            val json = JSONFactory200.createKycDocumentsJSON(kycDocuments)
            (json, HttpCode.`200`(callContext))
          }
        }
      }
    }


  resourceDocs += ResourceDoc(
      getKycMedia,
      apiVersion,
      "getKycMedia",
      "GET",
      "/customers/CUSTOMER_ID/kyc_media",
      "Get KYC Media for a customer",
      s"""Get KYC media (scans, pictures, videos) that affirms the identity of the customer.
        |
        |${authenticationRequiredMessage(true)}""".stripMargin,
      emptyObjectJson,
      kycMediasJSON,
      List(UserNotLoggedIn, CustomerNotFoundByCustomerId, UnknownError),
    List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
    Some(List(canGetAnyKycMedia)))

    lazy val getKycMedia  : OBPEndpoint = {
      case "customers" :: customerId :: "kyc_media" :: Nil JsonGet _ => {
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement("", u.userId, ApiRole.canGetAnyKycMedia, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            (kycMedias, callContxt) <- NewStyle.function.getKycMedias(customerId, callContext)
          } yield {
            val json = JSONFactory200.createKycMediasJSON(kycMedias)
            (json, HttpCode.`200`(callContext))
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      getKycChecks,
      apiVersion,
      "getKycChecks",
      "GET",
      "/customers/CUSTOMER_ID/kyc_checks",
      "Get Customer KYC Checks",
      s"""Get KYC checks for the Customer specified by CUSTOMER_ID.
        |
        |${authenticationRequiredMessage(true)}""".stripMargin,
      emptyObjectJson,
      kycChecksJSON,
      List(UserNotLoggedIn, CustomerNotFoundByCustomerId, UnknownError),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canGetAnyKycChecks))
    )

    lazy val getKycChecks  : OBPEndpoint = {
      case "customers" :: customerId :: "kyc_checks" :: Nil JsonGet _ => {
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement("", u.userId, ApiRole.canGetAnyKycChecks, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            (kycChecks, callContxt) <- NewStyle.function.getKycChecks(customerId, callContext)
          } yield {
            val json = JSONFactory200.createKycChecksJSON(kycChecks)
            (json, HttpCode.`200`(callContext))
          }
        }
      }
    }
    resourceDocs += ResourceDoc(
      getKycStatuses,
      apiVersion,
      "getKycStatuses",
      "GET",
      "/customers/CUSTOMER_ID/kyc_statuses",
      "Get Customer KYC statuses",
      s"""Get the KYC statuses for a customer specified by CUSTOMER_ID over time.
        |
        |${authenticationRequiredMessage(true)}""".stripMargin,
      emptyObjectJson,
      kycStatusesJSON,
      List(UserNotLoggedIn, CustomerNotFoundByCustomerId, UnknownError),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canGetAnyKycStatuses))
    )

    lazy val getKycStatuses  : OBPEndpoint = {
      case "customers" :: customerId :: "kyc_statuses" :: Nil JsonGet _ => {
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement("", u.userId, ApiRole.canGetAnyKycStatuses, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            (kycStatuses, callContxt) <- NewStyle.function.getKycStatuses(customerId, callContext)
          } yield {
            val json = JSONFactory200.createKycStatusesJSON(kycStatuses)
            (json, HttpCode.`200`(callContext))
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      getSocialMediaHandles,
      apiVersion,
      "getSocialMediaHandles",
      "GET",
      "/banks/BANK_ID/customers/CUSTOMER_ID/social_media_handles",
      "Get Customer Social Media Handles",
      s"""Get social media handles for a customer specified by CUSTOMER_ID.
        |
        |${authenticationRequiredMessage(true)}""".stripMargin,
      emptyObjectJson,
      socialMediasJSON,
      List(UserNotLoggedIn, UserHasMissingRoles, CustomerNotFoundByCustomerId, UnknownError),
      List(apiTagCustomer),
      Some(List(canGetSocialMediaHandles)))

    lazy val getSocialMediaHandles  : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: customerId :: "social_media_handles" :: Nil JsonGet _ => {
        cc => {
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound
            _ <- NewStyle.function.ownEntitlement(bank.bankId.value, u.userId, canGetSocialMediaHandles, cc.callContext)
            customer <- CustomerX.customerProvider.vend.getCustomerByCustomerId(customerId) ?~! ErrorMessages.CustomerNotFoundByCustomerId
          } yield {
            val kycSocialMedias = SocialMediaHandle.socialMediaHandleProvider.vend.getSocialMedias(customer.number)
            val json = JSONFactory200.createSocialMediasJSON(kycSocialMedias)
            successJsonResponse(Extraction.decompose(json))
          }
        }
      }
    }




    resourceDocs += ResourceDoc(
      addKycDocument,
      apiVersion,
      "addKycDocument",
      "PUT",
      "/banks/BANK_ID/customers/CUSTOMER_ID/kyc_documents/KYC_DOCUMENT_ID",
      "Add KYC Document",
      "Add a KYC document for the customer specified by CUSTOMER_ID. KYC Documents contain the document type (e.g. passport), place of issue, expiry etc. ",
      postKycDocumentJSON,
      kycDocumentJSON,
      List(UserNotLoggedIn, InvalidJsonFormat, BankNotFound, CustomerNotFoundByCustomerId,"Server error: could not add KycDocument", UnknownError),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canAddKycDocument))
    )

    // TODO customerNumber should be in the url but not also in the postedData

    lazy val addKycDocument : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: customerId :: "kyc_documents" :: documentId :: Nil JsonPut json -> _ => {
        // customerNumber is duplicated in postedData. remove from that?
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement(bankId.value, u.userId, ApiRole.canAddKycDocument, callContext)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            failMsg = s"$InvalidJsonFormat The Json body should be the $PostKycDocumentJSON "
            postedData <- NewStyle.function.tryons(failMsg, 400, callContext) {
              json.extract[PostKycDocumentJSON]
            }

            (kycDocumentCreated, callContext) <-
              NewStyle.function.createOrUpdateKycDocument(
                bankId.value,
                customerId,
                documentId,
                postedData.customer_number,
                postedData.`type`,
                postedData.number,
                postedData.issue_date,
                postedData.issue_place,
                postedData.expiry_date,
                callContext)
          } yield {
            val json = JSONFactory200.createKycDocumentJSON(kycDocumentCreated)
            (json, HttpCode.`201`(callContext))
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      addKycMedia,
      apiVersion,
      "addKycMedia",
      "PUT",
      "/banks/BANK_ID/customers/CUSTOMER_ID/kyc_media/KYC_MEDIA_ID",
      "Add KYC Media",
      "Add some KYC media for the customer specified by CUSTOMER_ID. KYC Media resources relate to KYC Documents and KYC Checks and contain media urls for scans of passports, utility bills etc",
      postKycMediaJSON,
      kycMediaJSON,
      List(UserNotLoggedIn, InvalidJsonFormat, CustomerNotFoundByCustomerId, ServerAddDataError, UnknownError),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canAddKycMedia))
    )

    lazy val addKycMedia : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: customerId :: "kyc_media" :: mediaId :: Nil JsonPut json -> _ => {
        // customerNumber is in url and duplicated in postedData. remove from that?
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement(bankId.value, u.userId, ApiRole.canAddKycMedia, callContext)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            failMsg = s"$InvalidJsonFormat The Json body should be the $PostKycMediaJSON "
            postedData <- NewStyle.function.tryons(failMsg, 400, callContext) {
              json.extract[PostKycMediaJSON]
            }

            (kycMediaCreated, callContext) <- NewStyle.function.createOrUpdateKycMedia(
                bankId.value,
                customerId,
                mediaId,
                postedData.customer_number,
                postedData.`type`,
                postedData.url,
                postedData.date,
                postedData.relates_to_kyc_document_id,
                postedData.relates_to_kyc_check_id,
              callContext
            )
          } yield {
            val json = JSONFactory200.createKycMediaJSON(kycMediaCreated)
            (json, HttpCode.`201`(callContext))
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      addKycCheck,
      apiVersion,
      "addKycCheck",
      "PUT",
      "/banks/BANK_ID/customers/CUSTOMER_ID/kyc_check/KYC_CHECK_ID",
      "Add KYC Check",
      "Add a KYC check for the customer specified by CUSTOMER_ID. KYC Checks store details of checks on a customer made by the KYC team, their comments and a satisfied status",
      postKycCheckJSON,
      kycCheckJSON,
      List(UserNotLoggedIn, InvalidJsonFormat, BankNotFound, CustomerNotFoundByCustomerId, ServerAddDataError, UnknownError),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canAddKycCheck))
    )

    lazy val addKycCheck : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: customerId :: "kyc_check" :: checkId :: Nil JsonPut json -> _ => {
        // customerNumber is in url and duplicated in postedData. remove from that?
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement(bankId.value, u.userId, ApiRole.canAddKycCheck, callContext)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            failMsg = s"$InvalidJsonFormat The Json body should be the $PostKycCheckJSON "
            postedData <- NewStyle.function.tryons(failMsg, 400, callContext) {
              json.extract[PostKycCheckJSON]
            }

            (kycCheck, callContext) <- NewStyle.function.createOrUpdateKycCheck(
                                    bankId.value,
                                    customerId,
                                    checkId,
                                    postedData.customer_number,
                                    postedData.date,
                                    postedData.how,
                                    postedData.staff_user_id,
                                    postedData.staff_name,
                                    postedData.satisfied,
                                    postedData.comments,
                                    callContext
                                    )
          } yield {
            val json = JSONFactory200.createKycCheckJSON(kycCheck)
            (json, HttpCode.`201`(callContext))
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      addKycStatus,
      apiVersion,
      "addKycStatus",
      "PUT",
      "/banks/BANK_ID/customers/CUSTOMER_ID/kyc_statuses",
      "Add KYC Status",
      "Add a kyc_status for the customer specified by CUSTOMER_ID. KYC Status is a timeline of the KYC status of the customer",
      postKycStatusJSON,
      kycStatusJSON,
      List(UserNotLoggedIn, InvalidJsonFormat, InvalidBankIdFormat,UnknownError, BankNotFound ,ServerAddDataError ,CustomerNotFoundByCustomerId),
      List(apiTagKyc, apiTagCustomer, apiTagNewStyle),
      Some(List(canAddKycStatus))
    )

    lazy val addKycStatus : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: customerId :: "kyc_statuses" :: Nil JsonPut json -> _ => {
        // customerNumber is in url and duplicated in postedData. remove from that?
        cc => {
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement(bankId.value, u.userId, ApiRole.canAddKycStatus, callContext)
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (customer, callContext) <- NewStyle.function.getCustomerByCustomerId(customerId, callContext)
            failMsg = s"$InvalidJsonFormat The Json body should be the $PostKycStatusJSON "
            postedData <- NewStyle.function.tryons(failMsg, 400, callContext) {
              json.extract[PostKycStatusJSON]
            }

            (kycStatus, callContext) <- NewStyle.function.createOrUpdateKycStatus(
                bankId.value,
                customerId,
                postedData.customer_number,
                postedData.ok,
                postedData.date, callContext)
          } yield {
            val json = JSONFactory200.createKycStatusJSON(kycStatus)
            (json, HttpCode.`201`(callContext))
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      addSocialMediaHandle,
      apiVersion,
      "addSocialMediaHandle",
      "POST",
      "/banks/BANK_ID/customers/CUSTOMER_ID/social_media_handles",
      "Add Social Media Handle",
      "Add a social media handle for the customer specified by CUSTOMER_ID",
      socialMediaJSON,
      successMessage,
      List(
        UserNotLoggedIn,
        InvalidJsonFormat, 
        InvalidBankIdFormat, 
        UserHasMissingRoles,
        CustomerNotFoundByCustomerId,
        UnknownError),
      List(apiTagCustomer),
      Some(List(canAddSocialMediaHandle))
    )

    lazy val addSocialMediaHandle : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: customerId :: "social_media_handles" :: Nil JsonPost json -> _ => {
        // customerNumber is in url and duplicated in postedData. remove from that?
        cc => {
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            postedData <- tryo{json.extract[SocialMediaJSON]} ?~! ErrorMessages.InvalidJsonFormat
            _ <- tryo(assert(isValidID(bankId.value)))?~! ErrorMessages.InvalidBankIdFormat
            (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound
            _ <- NewStyle.function.ownEntitlement(bank.bankId.value, u.userId, canAddSocialMediaHandle, cc.callContext)
            _ <- CustomerX.customerProvider.vend.getCustomerByCustomerId(customerId) ?~! ErrorMessages.CustomerNotFoundByCustomerId
            _ <- booleanToBox(
              SocialMediaHandle.socialMediaHandleProvider.vend.addSocialMedias(
                postedData.customer_number,
                postedData.`type`,
                postedData.handle,
                postedData.date_added,
                postedData.date_activated),
              "Server error: could not add")
          } yield {
            successJsonResponse(Extraction.decompose(successMessage), 201)
          }
        }
      }
    }

    resourceDocs += ResourceDoc(
      getCoreAccountById,
      apiVersion,
      "getCoreAccountById",
      "GET",
      "/my/banks/BANK_ID/accounts/ACCOUNT_ID/account",
      "Get Account by Id (Core)",
      s"""Information returned about the account specified by ACCOUNT_ID:
        |
        |* Number
        |* Owners
        |* Type
        |* Balance
        |* IBAN
        |
        |This call returns the owner view and requires access to that view.
        |
        |
        |${authenticationRequiredMessage(true)}
        |      
        |""".stripMargin,
      emptyObjectJson,
      moderatedCoreAccountJSON,
      List(BankAccountNotFound,UnknownError),
      apiTagAccount :: apiTagPsd2 ::  Nil)

    lazy val getCoreAccountById : OBPEndpoint = {
      //get account by id (assume owner view requested)
      case "my" :: "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "account" :: Nil JsonGet req => {

        cc =>
          // TODO return specific error if bankId == "BANK_ID" or accountId == "ACCOUNT_ID"
          // Should be a generic guard we can use for all calls (also for userId etc.)
          for {
            u <- cc.user ?~  UserNotLoggedIn
            account <- BankAccountX(bankId, accountId) ?~ BankAccountNotFound
            // Assume owner view was requested
            view <- u.checkOwnerViewAccessAndReturnOwnerView(BankIdAccountId(account.bankId, account.accountId))
            moderatedAccount <- account.moderatedBankAccount(view, BankIdAccountId(bankId, accountId), cc.user, Some(cc))
          } yield {
            val moderatedAccountJson = JSONFactory200.createCoreBankAccountJSON(moderatedAccount)
            val response = successJsonResponse(Extraction.decompose(moderatedAccountJson))
            response
          }
      }
    }



    resourceDocs += ResourceDoc(
      getCoreTransactionsForBankAccount,
      apiVersion,
      "getCoreTransactionsForBankAccount",
      "GET",
      "/my/banks/BANK_ID/accounts/ACCOUNT_ID/transactions",
      "Get Transactions for Account (Core)",
      s"""Returns transactions list (Core info) of the account specified by ACCOUNT_ID.
        |
        |Authentication is required.
        |
        |${urlParametersDocument(true, true)}
        |
        |""",
      emptyObjectJson,
      coreTransactionsJSON,
      List(BankAccountNotFound, UnknownError),
      List(apiTagTransaction, apiTagAccount, apiTagPsd2))
    
    //Note: we already have the method: getTransactionsForBankAccount in V121.
    //The only difference here is "Core implies 'owner' view" 
    lazy val getCoreTransactionsForBankAccount : OBPEndpoint = {
      //get transactions
      case "my" :: "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "transactions" :: Nil JsonGet req => {
        cc =>
          for {
            u <- cc.user ?~  UserNotLoggedIn
            params <- createQueriesByHttpParams(req.request.headers)
            (bank, callContext) <- BankX(bankId, Some(cc)) ?~ BankNotFound
            bankAccount <- BankAccountX(bankId, accountId) ?~! BankAccountNotFound
            view <- u.checkOwnerViewAccessAndReturnOwnerView(BankIdAccountId(bankAccount.bankId,bankAccount.accountId))
            (transactions, callContext) <- bankAccount.getModeratedTransactions(bank, cc.user, view, BankIdAccountId(bankId, accountId), None, params)
          } yield {
            val json = JSONFactory200.createCoreTransactionsJSON(transactions)
            successJsonResponse(Extraction.decompose(json))
          }
      }
    }


    // Copied from 1.2.1 and modified

    resourceDocs += ResourceDoc(
      accountById,
      apiVersion,
      "accountById",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/account",
      "Get Account by Id (Full)",
      s"""Information returned about an account specified by ACCOUNT_ID as moderated by the view (VIEW_ID):
        |
        |* Number
        |* Owners
        |* Type
        |* Balance
        |* IBAN
        |* Available views (sorted by short_name)
        |
        |More details about the data moderation by the view [here](#1_2_1-getViewsForBankAccount).
        |
        |PSD2 Context: PSD2 requires customers to have access to their account information via third party applications.
        |This call provides balance and other account information via delegated authentication using OAuth.
        |
        |${authenticationRequiredMessage(true)} if the 'is_public' field in view (VIEW_ID) is not set to `true`.
        |
        |""".stripMargin,
      emptyObjectJson,
      moderatedAccountJSON,
      List(BankNotFound,AccountNotFound,ViewNotFound, UserNoPermissionAccessView, UnknownError),
      apiTagAccount ::  Nil)

    lazy val accountById : OBPEndpoint = {
      //get account by id
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "account" :: Nil JsonGet req => {
        cc =>
          for {
            u <- cc.user ?~! UserNotLoggedIn
            (bank, callContext) <- BankX(bankId, Some(cc)) ?~ BankNotFound // Check bank exists.
            account <- BankAccountX(bank.bankId, accountId) ?~ {ErrorMessages.AccountNotFound} // Check Account exists.
            availableViews <- Full(Views.views.vend.privateViewsUserCanAccessForAccount(u, BankIdAccountId(account.bankId, account.accountId)))
            view <- APIUtil.checkViewAccessAndReturnView(viewId, BankIdAccountId(account.bankId, account.accountId), Some(u))
            moderatedAccount <- account.moderatedBankAccount(view, BankIdAccountId(bankId, accountId), cc.user, callContext)
          } yield {
            val viewsAvailable = availableViews.map(JSONFactory121.createViewJSON).sortBy(_.short_name)
            val moderatedAccountJson = JSONFactory121.createBankAccountJSON(moderatedAccount, viewsAvailable)
            successJsonResponse(Extraction.decompose(moderatedAccountJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      getPermissionsForBankAccount,
      apiVersion,
      "getPermissionsForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions",
      "Get access",
      s"""Returns the list of the permissions at BANK_ID for account ACCOUNT_ID, with each time a pair composed of the user and the views that he has access to.
        |
        |${authenticationRequiredMessage(true)}
        |and the user needs to have access to the owner view.
        |
        |""",
      emptyObjectJson,
      permissionsJSON,
      List(UserNotLoggedIn, BankNotFound, AccountNotFound ,UnknownError),
      List(apiTagView, apiTagAccount, apiTagUser, apiTagEntitlement, apiTagNewStyle)
    )

    lazy val getPermissionsForBankAccount : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: Nil JsonGet req => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            (_, callContext) <- NewStyle.function.getBank(bankId, callContext)
            (account, callContext) <- NewStyle.function.getBankAccount(bankId, accountId, callContext)
            permissions <- NewStyle.function.permissions(account, u)
          } yield {
            val permissionsJSON = JSONFactory121.createPermissionsJSON(permissions.sortBy(_.user.emailAddress))
            (permissionsJSON, HttpCode.`200`(callContext))
          }
      }
    }

    resourceDocs += ResourceDoc(
      getPermissionForUserForBankAccount,
      apiVersion,
      "getPermissionForUserForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions/PROVIDER/PROVIDER_ID",
      "Get Account access for User",
      s"""Returns the list of the views at BANK_ID for account ACCOUNT_ID that a user identified by PROVIDER_ID at their provider PROVIDER has access to.
        |All url parameters must be [%-encoded](http://en.wikipedia.org/wiki/Percent-encoding), which is often especially relevant for USER_ID and PROVIDER.
        |
        |${authenticationRequiredMessage(true)}
        |
        |The user needs to have access to the owner view.""",
      emptyObjectJson,
      viewsJSONV121,
      List(UserNotLoggedIn,BankNotFound, AccountNotFound,UnknownError),
      List(apiTagView, apiTagAccount, apiTagUser))

    lazy val getPermissionForUserForBankAccount : OBPEndpoint = {
      //get access for specific user
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: provider :: providerId :: Nil JsonGet req => {
        cc =>
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn // Check we have a user (rather than error or empty)
            (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound // Check bank exists.
            account <- BankAccountX(bank.bankId, accountId) ?~! {ErrorMessages.AccountNotFound} // Check Account exists.
            permission <- account permission(u, provider, providerId)
          } yield {
            // TODO : Note this is using old createViewsJSON without can_add_counterparty etc.
            val views = JSONFactory121.createViewsJSON(permission.views.sortBy(_.viewId.value))
            successJsonResponse(Extraction.decompose(views))
          }
      }
    }



    resourceDocs += ResourceDoc(
      createAccount,
      apiVersion,
      "createAccount",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID",
      "Create Account",
      """Create Account at bank specified by BANK_ID with Id specified by ACCOUNT_ID.
        |
        |
        |The User can create an Account for themself or an Account for another User if they have CanCreateAccount role.
        |
        |If USER_ID is not specified the account will be owned by the logged in User.
        |
        |ACCOUNT_ID SHOULD be a UUID. ACCOUNT_ID MUST NOT be the ACCOUNT_NUMBER.
        |
        |TYPE SHOULD be the PRODUCT_CODE from Product.
        |
        |Note: The Amount must be zero.""".stripMargin,
      CreateAccountJSON("A user_id","CURRENT", "Label", AmountOfMoneyJSON121("EUR", "0")),
      coreAccountJSON,
      List(
        UserNotLoggedIn,
        InvalidJsonFormat,
        InvalidUserId,
        InvalidAccountIdFormat,
        InvalidBankIdFormat,
        UserNotFoundById,
        InvalidAccountBalanceAmount,
        InvalidAccountType,
        InvalidAccountInitialBalance,
        InvalidAccountBalanceCurrency,
        UnknownError
      ),
      List(apiTagAccount),
      Some(List(canCreateAccount))
    )

    apiRelations += ApiRelation(createAccount, createAccount, "self")
    apiRelations += ApiRelation(createAccount, getCoreAccountById, "detail")

    // Note: This doesn't currently work (links only have access to same version resource docs). TODO fix me.
    apiRelations += ApiRelation(createAccount, Implementations1_2_1.updateAccountLabel, "update_label")


    lazy val createAccount : OBPEndpoint = {
      // Create a new account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: Nil JsonPut json -> _ => {
        cc =>{

          for {
            loggedInUser <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            jsonBody <- tryo (json.extract[CreateAccountJSON]) ?~! ErrorMessages.InvalidJsonFormat
            user_id <- tryo (if (jsonBody.user_id.nonEmpty) jsonBody.user_id else loggedInUser.userId) ?~! ErrorMessages.InvalidUserId
            _ <- tryo(assert(isValidID(accountId.value)))?~! ErrorMessages.InvalidAccountIdFormat
            _ <- tryo(assert(isValidID(bankId.value)))?~! ErrorMessages.InvalidBankIdFormat
            postedOrLoggedInUser <- UserX.findByUserId(user_id) ?~! ErrorMessages.UserNotFoundById
            (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! s"Bank $bankId not found"
            // User can create account for self or an account for another user if they have CanCreateAccount role
            _ <- if (user_id == loggedInUser.userId) Full(Unit)
                else NewStyle.function.ownEntitlement(bankId.value, loggedInUser.userId, canCreateAccount, callContext, s"User must either create account for self or have role $CanCreateAccount")

            initialBalanceAsString <- tryo (jsonBody.balance.amount) ?~! ErrorMessages.InvalidAccountBalanceAmount
            accountType <- tryo(jsonBody.`type`) ?~! ErrorMessages.InvalidAccountType
            accountLabel <- tryo(jsonBody.`type`) //?~! ErrorMessages.InvalidAccountLabel // TODO looks strange.
            initialBalanceAsNumber <- tryo {BigDecimal(initialBalanceAsString)} ?~! ErrorMessages.InvalidAccountInitialBalance
            _ <- booleanToBox(0 == initialBalanceAsNumber) ?~! s"Initial balance must be zero"
            currency <- tryo (jsonBody.balance.currency) ?~! ErrorMessages.InvalidAccountBalanceCurrency
            // TODO Since this is a PUT, we should replace the resource if it already exists but will need to check persmissions
            _ <- booleanToBox(BankAccountX(bankId, accountId).isEmpty,
              s"Account with id $accountId already exists at bank $bankId")
            bankAccount <- Connector.connector.vend.createBankAccountLegacy(
              bankId, accountId, accountType, 
              accountLabel, currency, initialBalanceAsNumber, 
              postedOrLoggedInUser.name,
              "", //added new field in V220
              List.empty
            )
          } yield {
            BankAccountCreation.setAccountHolderAndRefreshUserAccountAccess(bankId, accountId, postedOrLoggedInUser, Some(cc))

            val dataContext = DataContext(cc.user, Some(bankAccount.bankId), Some(bankAccount.accountId), Empty, Empty, Empty)
            val links = code.api.util.APIUtil.getHalLinks(CallerContext(createAccount), codeContext, dataContext)
            val json = JSONFactory200.createCoreAccountJSON(bankAccount, links)

            successJsonResponse(Extraction.decompose(json))
          }
        }
      }
    }



    val getTransactionTypesIsPublic = APIUtil.getPropsAsBoolValue("apiOptions.getTransactionTypesIsPublic", true)


    resourceDocs += ResourceDoc(
      getTransactionTypes,
      apiVersion,
      "getTransactionTypes",
      "GET",
      "/banks/BANK_ID/transaction-types",
      "Get Transaction Types at Bank",
      // TODO get the documentation of the parameters from the scala doc of the case class we return
      s"""Get Transaction Types for the bank specified by BANK_ID:
          |
          |Lists the possible Transaction Types available at the bank (as opposed to Transaction Request Types which are the possible ways Transactions can be created by this API Server).
          |
          |  * id : Unique transaction type id across the API instance. SHOULD be a UUID. MUST be unique.
          |  * bank_id : The bank that supports this TransactionType
          |  * short_code : A short code (SHOULD have no-spaces) which MUST be unique across the bank. May be stored with Transactions to link here
          |  * summary : A succinct summary
          |  * description : A longer description
          |  * charge : The charge to the customer for each one of these
          |
          |${authenticationRequiredMessage(!getTransactionTypesIsPublic)}""".stripMargin,
      emptyObjectJson,
      transactionTypesJsonV200,
      List(BankNotFound, UnknownError),
      List(apiTagBank, apiTagPSD2AIS, apiTagPsd2, apiTagNewStyle)
    )

    lazy val getTransactionTypes : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "transaction-types" :: Nil JsonGet _ => {
        cc => {
          for {
            // Get Transaction Types from the active provider
            (_, callContext) <- getTransactionTypesIsPublic match {
              case false => authenticatedAccess(cc)
              case true => anonymousAccess(cc)
            }
            (bank, callContext) <- NewStyle.function.getBank(bankId, callContext)
            transactionTypes <- Future(TransactionType.TransactionTypeProvider.vend.getTransactionTypesForBank(bank.bankId)) map { connectorEmptyResponse(_, callContext) } // ~> APIFailure("No transation types available. License may not be set.", 204)
          } yield {
            (JSONFactory200.createTransactionTypeJSON(transactionTypes), HttpCode.`200`(callContext))
          }
        }
      }
    }


    import net.liftweb.json.Extraction._
    import net.liftweb.json.JsonAST._
    val exchangeRates = prettyRender(decompose(fx.fallbackExchangeRates))

    resourceDocs += ResourceDoc(
      createTransactionRequest,
      apiVersion,
      "createTransactionRequest",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transaction-request-types/TRANSACTION_REQUEST_TYPE/transaction-requests",
      "Create Transaction Request",
      s"""Initiate a Payment via a Transaction Request.
        |
        |This is the preferred method to create a payment and supersedes makePayment in 1.2.1.
        |
        |PSD2 Context: Third party access access to payments is a core tenent of PSD2.
        |
        |This call satisfies that requirement from several perspectives:
        |
        |1) A transaction can be initiated by a third party application.
        |
        |2) The customer is informed of the charge that will incurred.
        |
        |3) The call uses delegated authentication (OAuth)
        |
        |See [this python code](https://github.com/OpenBankProject/Hello-OBP-DirectLogin-Python/blob/master/hello_payments.py) for a complete example of this flow.
        |
        |In sandbox mode, if the amount is less than 100 (any currency), the transaction request will create a transaction without a challenge, else the Transaction Request will be set to INITIALISED and a challenge will need to be answered.|
        |If a challenge is created you must answer it using Answer Transaction Request Challenge before the Transaction is created.
        |
        |You can transfer between different currency accounts. (new in 2.0.0). The currency in body must match the sending account.
        |
        |Currently TRANSACTION_REQUEST_TYPE must be set to SANDBOX_TAN
        |
        |The following static FX rates are available in sandbox mode:
        |
        |${exchangeRates}
        |
        |
        |The payer is set in the URL. Money comes out of the BANK_ID and ACCOUNT_ID specified in the URL
        |
        |The payee is set in the request body. Money goes into the BANK_ID and ACCOUNT_IDO specified in the request body.
        |
        |
        |${authenticationRequiredMessage(true)}
        |
        |""".stripMargin,
      transactionRequestBodyJsonV200,
      emptyObjectJson,
      List(
        UserNotLoggedIn,
        InvalidJsonFormat,
        InvalidBankIdFormat,
        InvalidAccountIdFormat,
        BankNotFound,
        AccountNotFound,
        ViewNotFound,
        UserNoPermissionAccessView,
        InsufficientAuthorisationToCreateTransactionRequest,
        CounterpartyNotFound,
        InvalidTransactionRequestType,
        InvalidTransactionRequestCurrency,
        TransactionDisabled,
        UnknownError
      ),
      List(apiTagTransactionRequest, apiTagPsd2),
      Some(List(canCreateAnyTransactionRequest)))

    lazy val createTransactionRequest: OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transaction-request-types" ::
        TransactionRequestType(transactionRequestType) :: "transaction-requests" :: Nil JsonPost json -> _ => {
        cc =>
          if (APIUtil.getPropsAsBoolValue("transactionRequests_enabled", false)) {
            for {
            /* TODO:
             * check if user has access using the view that is given (now it checks if user has access to owner view), will need some new permissions for transaction requests
             * test: functionality, error messages if user not given or invalid, if any other value is not existing
            */
              u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
              transBodyJson <- tryo{json.extract[TransactionRequestBodyJsonV200]} ?~! InvalidJsonFormat
              transBody <- tryo{getTransactionRequestBodyFromJson(transBodyJson)}
              _ <- tryo(assert(isValidID(bankId.value)))?~! InvalidBankIdFormat
              _ <- tryo(assert(isValidID(accountId.value)))?~! InvalidAccountIdFormat
              (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! BankNotFound
              fromAccount <- BankAccountX(bankId, accountId) ?~! AccountNotFound
              _ <-APIUtil.checkViewAccessAndReturnView(viewId, BankIdAccountId(fromAccount.bankId, fromAccount.accountId), Some(u)) match {
                case Full(_) =>
                  booleanToBox(u.hasOwnerViewAccess(BankIdAccountId(fromAccount.bankId,fromAccount.accountId)) == true)
                case _ =>
                  NewStyle.function.ownEntitlement(fromAccount.bankId.value, u.userId, canCreateAnyTransactionRequest, cc.callContext, InsufficientAuthorisationToCreateTransactionRequest)
              }
              toBankId <- tryo(BankId(transBodyJson.to.bank_id))
              toAccountId <- tryo(AccountId(transBodyJson.to.account_id))
              toAccount <- BankAccountX(toBankId, toAccountId) ?~! {ErrorMessages.CounterpartyNotFound}
              // Prevent default value for transaction request type (at least).
              // Get Transaction Request Types from Props "transactionRequests_supported_types". Default is empty string
              validTransactionRequestTypes <- tryo{APIUtil.getPropsValue("transactionRequests_supported_types", "")}
              // Use a list instead of a string to avoid partial matches
              validTransactionRequestTypesList <- tryo{validTransactionRequestTypes.split(",")}
              _ <- tryo(assert(transactionRequestType.value != "TRANSACTION_REQUEST_TYPE" && validTransactionRequestTypesList.contains(transactionRequestType.value))) ?~! s"${InvalidTransactionRequestType} : Invalid value is: '${transactionRequestType.value}' Valid values are: ${validTransactionRequestTypes}"
              _ <- tryo(assert(transBodyJson.value.currency == fromAccount.currency)) ?~! InvalidTransactionRequestCurrency
              createdTransactionRequest <- Connector.connector.vend.createTransactionRequestv200(u, fromAccount, toAccount, transactionRequestType, transBody)
            } yield {
              // Explicitly format as v2.0.0 json
              val json = JSONFactory200.createTransactionRequestWithChargeJSON(createdTransactionRequest)
              createdJsonResponse(Extraction.decompose(json))
            }
          } else {
            Full(errorJsonResponse(TransactionDisabled))
          }
      }
    }

    resourceDocs += ResourceDoc(
      answerTransactionRequestChallenge,
      apiVersion,
      "answerTransactionRequestChallenge",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transaction-request-types/TRANSACTION_REQUEST_TYPE/transaction-requests/TRANSACTION_REQUEST_ID/challenge",
      "Answer Transaction Request Challenge",
      """ 
        |In Sandbox mode, any string that can be converted to a positive integer will be accepted as an answer.
        |
      """.stripMargin,
      ChallengeAnswerJSON("89123812", "123345"),
      transactionRequestWithChargeJson,
      List(
        UserNotLoggedIn,
        InvalidAccountIdFormat,
          InvalidBankIdFormat,
        BankNotFound,
        UserNoPermissionAccessView,
        InvalidJsonFormat,
        InvalidTransactionRequestId,
        TransactionRequestTypeHasChanged,
        InvalidTransactionRequestChallengeId,
        TransactionRequestStatusNotInitiated,
        TransactionDisabled,
        UnknownError
      ),
      List(apiTagTransactionRequest, apiTagPsd2))

    lazy val answerTransactionRequestChallenge: OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transaction-request-types" ::
        TransactionRequestType(transactionRequestType) :: "transaction-requests" :: TransactionRequestId(transReqId) :: "challenge" :: Nil JsonPost json -> _ => {
        cc =>
          if (APIUtil.getPropsAsBoolValue("transactionRequests_enabled", false)) {
            for {
              u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
              _ <- tryo(assert(isValidID(accountId.value)))?~! ErrorMessages.InvalidAccountIdFormat
              _ <- tryo(assert(isValidID(bankId.value)))?~! ErrorMessages.InvalidBankIdFormat
              (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! BankNotFound
              fromAccount <- BankAccountX(bankId, accountId) ?~! AccountNotFound
              view <-APIUtil.checkViewAccessAndReturnView(viewId, BankIdAccountId(fromAccount.bankId, fromAccount.accountId), Some(u))
              _ <- if (u.hasOwnerViewAccess(BankIdAccountId(fromAccount.bankId,fromAccount.accountId))) Full(Unit)
                  else NewStyle.function.ownEntitlement(fromAccount.bankId.value, u.userId, canCreateAnyTransactionRequest, cc.callContext, InsufficientAuthorisationToCreateTransactionRequest)
              // Note: These checks are not in the ideal order. See version 2.1.0 which supercedes this

              answerJson <- tryo{json.extract[ChallengeAnswerJSON]} ?~! InvalidJsonFormat
              _ <- Connector.connector.vend.answerTransactionRequestChallenge(transReqId, answerJson.answer)
              //check the transReqId validation.
              (existingTransactionRequest, callContext) <- Connector.connector.vend.getTransactionRequestImpl(transReqId, callContext) ?~! s"${ErrorMessages.InvalidTransactionRequestId} : $transReqId"

              //check the input transactionRequestType is same as when the user create the existingTransactionRequest
              existingTransactionRequestType = existingTransactionRequest.`type`
              _ <- booleanToBox(existingTransactionRequestType.equals(transactionRequestType.value),s"${ErrorMessages.TransactionRequestTypeHasChanged} It should be :'$existingTransactionRequestType' ")

              //check the challenge id is same as when the user create the existingTransactionRequest
              _ <- booleanToBox(existingTransactionRequest.challenge.id.equals(answerJson.id),{ErrorMessages.InvalidTransactionRequestChallengeId})

              //check the challenge statue whether is initiated, only retreive INITIATED transaction requests.
              _ <- booleanToBox(existingTransactionRequest.status.equals("INITIATED"),ErrorMessages.TransactionRequestStatusNotInitiated)

              toBankId  = BankId(existingTransactionRequest.body.to_sandbox_tan.get.bank_id)
              toAccountId  = AccountId(existingTransactionRequest.body.to_sandbox_tan.get.account_id)
              toAccount <- BankAccountX(toBankId, toAccountId) ?~! s"$AccountNotFound,toBankId($toBankId) and toAccountId($toAccountId) is invalid ."
            
              //create transaction and insert its id into the transaction request
              transactionRequest <- Connector.connector.vend.createTransactionAfterChallengev200(fromAccount, toAccount, existingTransactionRequest)
            } yield {

              // Format explicitly as v2.0.0 json
              val json = JSONFactory200.createTransactionRequestWithChargeJSON(transactionRequest)
              //successJsonResponse(Extraction.decompose(json))

              val successJson = Extraction.decompose(json)
              successJsonResponse(successJson, 202)
            }
          } else {
            Full(errorJsonResponse(TransactionDisabled))
          }
      }
    }



    resourceDocs += ResourceDoc(
      getTransactionRequests,
      apiVersion,
      "getTransactionRequests",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transaction-requests",
      "Get Transaction Requests." ,
      """Returns transaction requests for account specified by ACCOUNT_ID at bank specified by BANK_ID.
        |
        |The VIEW_ID specified must be 'owner' and the user must have access to this view.
        |
        |Version 2.0.0 now returns charge information.
        |
        |Transaction Requests serve to initiate transactions that may or may not proceed. They contain information including:
        |
        |* Transaction Request Id
        |* Type
        |* Status (INITIATED, COMPLETED)
        |* Challenge (in order to confirm the request)
        |* From Bank / Account
        |* Body including To Account, Currency, Value, Description and other initiation information. (Could potentialy include a list of future transactions.)
        |* Related Transactions
        |
        |PSD2 Context: PSD2 requires transparency of charges to the customer.
        |This endpoint provides the charge that would be applied if the Transaction Request proceeds - and a record of that charge there after.
        |The customer can proceed with the Transaction by answering the security challenge.
        |
      """.stripMargin,
      emptyObjectJson,
      transactionRequestWithChargesJson,
      List(UserNotLoggedIn, BankNotFound, AccountNotFound, UserNoPermissionAccessView, UnknownError),
      List(apiTagTransactionRequest, apiTagPsd2))

    lazy val getTransactionRequests: OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transaction-requests" :: Nil JsonGet _ => {
        cc =>
          if (APIUtil.getPropsAsBoolValue("transactionRequests_enabled", false)) {
            for {
              u <- cc.user ?~! UserNotLoggedIn
              (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! BankNotFound
              fromAccount <- BankAccountX(bankId, accountId) ?~! AccountNotFound
              view <-APIUtil.checkViewAccessAndReturnView(viewId, BankIdAccountId(fromAccount.bankId, fromAccount.accountId), Some(u))
              transactionRequests <- Connector.connector.vend.getTransactionRequests(u, fromAccount)
            }
              yield {
                // Format the data as V2.0.0 json
                val json = JSONFactory200.createTransactionRequestJSONs(transactionRequests)
                successJsonResponse(Extraction.decompose(json))
              }
          } else {
            Full(errorJsonResponse(TransactionDisabled))
          }
      }
    }


    resourceDocs += ResourceDoc(
      createUser,
      apiVersion,
      "createUser",
      "POST",
      "/users",
      "Create User",
      s"""Creates OBP user.
        | No authorisation (currently) required.
        |
        | Mimics current webform to Register.
        |
        | Requires username(email) and password.
        |
        | Returns 409 error if username not unique.
        |
        | May require validation of email address.
        |
        |""",
      createUserJson,
      userJsonV200,
      List(UserNotLoggedIn, InvalidJsonFormat, InvalidStrongPasswordFormat ,"Error occurred during user creation.", "User with the same username already exists." , UnknownError),
      List(apiTagUser, apiTagOnboarding))

    lazy val createUser: OBPEndpoint = {
      case "users" :: Nil JsonPost json -> _ => {
        cc =>
          for {
            postedData <- tryo {json.extract[CreateUserJson]} ?~! ErrorMessages.InvalidJsonFormat
            _ <- tryo(assert(fullPasswordValidation(postedData.password))) ?~! ErrorMessages.InvalidStrongPasswordFormat
          } yield {
            if (AuthUser.find(By(AuthUser.username, postedData.username)).isEmpty) {
              val userCreated = AuthUser.create
                .firstName(postedData.first_name)
                .lastName(postedData.last_name)
                .username(postedData.username)
                .email(postedData.email)
                .password(postedData.password)
                .validated(APIUtil.getPropsAsBoolValue("user_account_validated", false))
              if(userCreated.validate.size > 0){
                Full(errorJsonResponse(userCreated.validate.map(_.msg).mkString(";")))
              }
              else
              {
                userCreated.saveMe()
                if (userCreated.saved_?) {
                  AuthUser.grantDefaultEntitlementsToAuthUser(userCreated)
                  val json = JSONFactory200.createUserJSONfromAuthUser(userCreated)
                  successJsonResponse(Extraction.decompose(json), 201)
                }
                else
                  Full(errorJsonResponse("Error occurred during user creation."))
              }
            }
            else {
              Full(errorJsonResponse("User with the same username already exists.", 409))
            }
          }
      }
    }



//    resourceDocs += ResourceDoc(
//      createMeeting,
//      apiVersion,
//      "createMeeting",
//      "POST",
//      "/banks/BANK_ID/meetings",
//      "Create Meeting (video conference/call)",
//      """Create Meeting: Initiate a video conference/call with the bank.
//        |
//        |The Meetings resource contains meta data about video/other conference sessions, not the video/audio/chat itself.
//        |
//        |The actual conferencing is handled by external providers. Currently OBP supports tokbox video conferences (WIP).
//        |
//        |This is not a recomendation of tokbox per se.
//        |
//        |provider_id determines the provider of the meeting / video chat service. MUST be url friendly (no spaces).
//        |
//        |purpose_id explains the purpose of the chat. onboarding | mortgage | complaint etc. MUST be url friendly (no spaces).
//        |
//        |Login is required.
//        |
//        |This call is **experimental**. Currently staff_user_id is not set. Further calls will be needed to correctly set this.
//      """.stripMargin,
//      CreateMeetingJson("tokbox", "onboarding"),
//      meetingJson,
//      List(
//        UserNotLoggedIn,
//        MeetingApiKeyNotConfigured,
//        MeetingApiSecretNotConfigured,
//        InvalidBankIdFormat,
//        BankNotFound,
//        InvalidJsonFormat,
//        MeetingsNotSupported,
//        UnknownError
//      ),
//      List(apiTagMeeting, apiTagCustomer, apiTagExperimental))
//
//
//    lazy val createMeeting: OBPEndpoint = {
//      case "banks" :: BankId(bankId) :: "meetings" :: Nil JsonPost json -> _ => {
//        cc =>
//          if (APIUtil.getPropsAsBoolValue("meeting.tokbox_enabled", false)) {
//            for {
//              // TODO use these keys to get session and tokens from tokbox
//              _ <- APIUtil.getPropsValue("meeting.tokbox_api_key") ~> APIFailure(MeetingApiKeyNotConfigured, 403)
//              _ <- APIUtil.getPropsValue("meeting.tokbox_api_secret") ~> APIFailure(MeetingApiSecretNotConfigured, 403)
//              u <- cc.user ?~! UserNotLoggedIn
//              _ <- tryo(assert(isValidID(bankId.value)))?~! InvalidBankIdFormat
//              (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound
//              postedData <- tryo {json.extract[CreateMeetingJson]} ?~! InvalidJsonFormat
//              now = Calendar.getInstance().getTime()
//              sessionId <- tryo{code.opentok.OpenTokUtil.getSession.getSessionId()}
//              customerToken <- tryo{code.opentok.OpenTokUtil.generateTokenForPublisher(60)}
//              staffToken <- tryo{code.opentok.OpenTokUtil.generateTokenForModerator(60)}
//              meeting <- Meetings.meetingProvider.vend.createMeeting(bank.bankId, u, u, postedData.provider_id, postedData.purpose_id, now, sessionId, customerToken, staffToken
//                                                                    ,null,null)//These two are used from V310
//            } yield {
//              // Format the data as V2.0.0 json
//              val json = JSONFactory200.createMeetingJSON(meeting)
//              successJsonResponse(Extraction.decompose(json), 201)
//            }
//          } else {
//            Full(errorJsonResponse(MeetingsNotSupported))
//          }
//      }
//    }
//
//
//    resourceDocs += ResourceDoc(
//      getMeetings,
//      apiVersion,
//      "getMeetings",
//      "GET",
//      "/banks/BANK_ID/meetings",
//      "Get Meetings",
//      """Meetings contain meta data about, and are used to facilitate, video conferences / chats etc.
//        |
//        |The actual conference/chats are handled by external services.
//        |
//        |Login is required.
//        |
//        |This call is **experimental** and will require further authorisation in the future.
//      """.stripMargin,
//      emptyObjectJson,
//      meetingsJson,
//      List(
//        UserNotLoggedIn,
//        MeetingApiKeyNotConfigured,
//        MeetingApiSecretNotConfigured,
//        BankNotFound,
//        MeetingsNotSupported,
//        UnknownError),
//      List(apiTagMeeting, apiTagCustomer, apiTagExperimental))
//
//
//    lazy val getMeetings: OBPEndpoint = {
//      case "banks" :: BankId(bankId) :: "meetings" :: Nil JsonGet _ => {
//        cc =>
//          if (APIUtil.getPropsAsBoolValue("meeting.tokbox_enabled", false)) {
//            for {
//              _ <- cc.user ?~! ErrorMessages.UserNotLoggedIn
//              (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! BankNotFound
//              _ <- APIUtil.getPropsValue("meeting.tokbox_api_key") ~> APIFailure(ErrorMessages.MeetingApiKeyNotConfigured, 403)
//              _ <- APIUtil.getPropsValue("meeting.tokbox_api_secret") ~> APIFailure(ErrorMessages.MeetingApiSecretNotConfigured, 403)
//              u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
//              (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound
//              // now = Calendar.getInstance().getTime()
//              meetings <- Meetings.meetingProvider.vend.getMeetings(bank.bankId, u)
//            }
//              yield {
//                // Format the data as V2.0.0 json
//                val json = JSONFactory200.createMeetingJSONs(meetings)
//                successJsonResponse(Extraction.decompose(json))
//              }
//          } else {
//            Full(errorJsonResponse(MeetingsNotSupported))
//          }
//      }
//    }
//
//
//
//    resourceDocs += ResourceDoc(
//      getMeeting,
//      apiVersion,
//      "getMeeting",
//      "GET",
//      "/banks/BANK_ID/meetings/MEETING_ID",
//      "Get Meeting",
//      """Get Meeting specified by BANK_ID / MEETING_ID
//        |Meetings contain meta data about, and are used to facilitate, video conferences / chats etc.
//        |
//        |The actual conference/chats are handled by external services.
//        |
//        |Login is required.
//        |
//        |This call is **experimental** and will require further authorisation in the future.
//      """.stripMargin,
//      emptyObjectJson,
//      meetingJson,
//      List(
//        UserNotLoggedIn, 
//        BankNotFound, 
//        MeetingApiKeyNotConfigured,
//        MeetingApiSecretNotConfigured, 
//        MeetingNotFound, 
//        MeetingsNotSupported,
//        UnknownError
//      ),
//      List(apiTagMeeting, apiTagKyc, apiTagCustomer, apiTagExperimental))
//
//
//    lazy val getMeeting: OBPEndpoint = {
//      case "banks" :: BankId(bankId) :: "meetings" :: meetingId :: Nil JsonGet _ => {
//        cc =>
//          if (APIUtil.getPropsAsBoolValue("meeting.tokbox_enabled", false)) {
//            for {
//              u <- cc.user ?~! UserNotLoggedIn
//              (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! BankNotFound
//              _ <- APIUtil.getPropsValue("meeting.tokbox_api_key") ~> APIFailure(ErrorMessages.MeetingApiKeyNotConfigured, 403)
//              _ <- APIUtil.getPropsValue("meeting.tokbox_api_secret") ~> APIFailure(ErrorMessages.MeetingApiSecretNotConfigured, 403)
//              (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound
//              meeting <- Meetings.meetingProvider.vend.getMeeting(bank.bankId, u, meetingId)  ?~! {ErrorMessages.MeetingNotFound}
//            }
//              yield {
//                // Format the data as V2.0.0 json
//                val json = JSONFactory200.createMeetingJSON(meeting)
//                successJsonResponse(Extraction.decompose(json))
//              }
//          } else {
//            Full(errorJsonResponse(ErrorMessages.MeetingsNotSupported))
//          }
//      }
//    }


    resourceDocs += ResourceDoc(
      createCustomer,
      apiVersion,
      "createCustomer",
      "POST",
      "/banks/BANK_ID/customers",
      "Create Customer",
      s"""Add a customer linked to the user specified by user_id
        |The Customer resource stores the customer number, legal name, email, phone number, their date of birth, relationship status, education attained, a url for a profile image, KYC status etc.
        |This call may require additional permissions/role in the future.
        |For now the authenticated user can create at most one linked customer.
        |Dates need to be in the format 2013-01-21T23:08:00Z
        |${authenticationRequiredMessage(true)}
        |""",
      createCustomerJson,
      customerJsonV140,
      List(
        InvalidBankIdFormat,
        UserNotLoggedIn,
        BankNotFound,
        CustomerNumberAlreadyExists,
        UserHasMissingRoles,
        UserNotFoundById,
        CreateConsumerError,
        CustomerAlreadyExistsForUser,
        CreateUserCustomerLinksError,
        UnknownError
      ),
      List(apiTagCustomer, apiTagPerson),
      Some(List(canCreateCustomer,canCreateUserCustomerLink)))



    // TODO
    // Separate customer creation (keep here) from customer linking (remove from here)
    // Remove user_id from CreateCustomerJson
    // Logged in user must have CanCreateCustomer (should no longer be able create customer for own user)
    // Add ApiLink to createUserCustomerLink

    lazy val createCustomer : OBPEndpoint = {
      case "banks" :: BankId(bankId) :: "customers" :: Nil JsonPost json -> _ => {
        cc =>
          for {
            u <- cc.user ?~! UserNotLoggedIn// TODO. CHECK user has role to create a customer / create a customer for another user id.
            _ <- tryo(assert(isValidID(bankId.value)))?~! ErrorMessages.InvalidBankIdFormat
            (bank, callContext ) <- BankX(bankId, Some(cc)) ?~! BankNotFound
            postedData <- tryo{json.extract[CreateCustomerJson]} ?~! ErrorMessages.InvalidJsonFormat
            _ <- Helper.booleanToBox(
              !`checkIfContains::::` (postedData.customer_number), s"$InvalidJsonFormat customer_number can not contain `::::` characters")
            requiredEntitlements = canCreateCustomer ::
                                   canCreateUserCustomerLink ::
                                   Nil
            requiredEntitlementsTxt = requiredEntitlements.mkString(" and ")
            _ <- NewStyle.function.hasAllEntitlements(bankId.value, u.userId, requiredEntitlements, callContext)
            _ <- tryo(assert(CustomerX.customerProvider.vend.checkCustomerNumberAvailable(bankId, postedData.customer_number) == true)) ?~! ErrorMessages.CustomerNumberAlreadyExists
            user_id <- tryo (if (postedData.user_id.nonEmpty) postedData.user_id else u.userId) ?~! s"Problem getting user_id"
            _ <- UserX.findByUserId(user_id) ?~! ErrorMessages.UserNotFoundById
            customer <- CustomerX.customerProvider.vend.addCustomer(bankId,
              postedData.customer_number,
              postedData.legal_name,
              postedData.mobile_phone_number,
              postedData.email,
              CustomerFaceImage(postedData.face_image.date, postedData.face_image.url),
              postedData.date_of_birth,
              postedData.relationship_status,
              postedData.dependants,
              postedData.dob_of_dependants,
              postedData.highest_education_attained,
              postedData.employment_status,
              postedData.kyc_status,
              postedData.last_ok_date,
              None,
              None,
              "",
              "",
              "") ?~! CreateConsumerError
            _ <- booleanToBox(UserCustomerLink.userCustomerLink.vend.getUserCustomerLink(user_id, customer.customerId).isEmpty == true) ?~! ErrorMessages.CustomerAlreadyExistsForUser
            _ <- UserCustomerLink.userCustomerLink.vend.createUserCustomerLink(user_id, customer.customerId, new Date(), true) ?~! CreateUserCustomerLinksError
          } yield {
            val json = JSONFactory1_4_0.createCustomerJson(customer)
            val successJson = Extraction.decompose(json)
            successJsonResponse(successJson, 201)
          }
      }
    }



    resourceDocs += ResourceDoc(
      getCurrentUser,
      apiVersion,
      "getCurrentUser", // TODO can we get this string from the val two lines above?
      "GET",
      "/users/current",
      "Get User (Current)",
      """Get the logged in user
        |
        |Login is required.
      """.stripMargin,
      emptyObjectJson,
      userJsonV200,
      List(UserNotLoggedIn, UnknownError),
      List(apiTagUser))


    lazy val getCurrentUser: OBPEndpoint = {
      case "users" :: "current" :: Nil JsonGet _ => {
        cc =>
            for {
              u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            }
              yield {
                // Format the data as V2.0.0 json
                val json = JSONFactory200.createUserJSON(u)
                successJsonResponse(Extraction.decompose(json))
              }
      }
    }


    resourceDocs += ResourceDoc(
      getUser,
      apiVersion,
      "getUser",
      "GET",
      "/users/USER_EMAIL",
      "Get Users by Email Address",
      """Get users by email address
        |
        |Login is required.
        |CanGetAnyUser entitlement is required,
        |
      """.stripMargin,
      emptyObjectJson,
      usersJsonV200,
      List(UserNotLoggedIn, UserHasMissingRoles, UserNotFoundByEmail, UnknownError),
      List(apiTagUser),
      Some(List(canGetAnyUser)))


    lazy val getUser: OBPEndpoint = {
      case "users" :: userEmail :: Nil JsonGet _ => {
        cc =>
            for {
              l <- cc.user ?~! ErrorMessages.UserNotLoggedIn
              _ <- NewStyle.function.ownEntitlement("", l.userId, ApiRole.canGetAnyUser, cc.callContext)
                // Workaround to get userEmail address directly from URI without needing to URL-encode it
              users <- tryo{AuthUser.getResourceUsersByEmail(CurrentReq.value.uri.split("/").last)} ?~! {ErrorMessages.UserNotFoundByEmail}
            }
              yield {
                // Format the data as V2.0.0 json
                val json = JSONFactory200.createUserJSONs(users)
                successJsonResponse(Extraction.decompose(json))
              }
      }
    }



    // createUserCustomerLinks
    val createUserCustomerLinksEntitlementsRequiredForSpecificBank = canCreateUserCustomerLink :: Nil
    val createUserCustomerLinksEntitlementsRequiredForAnyBank = canCreateUserCustomerLinkAtAnyBank :: Nil
    val createUserCustomerLinksrequiredEntitlementsText = createUserCustomerLinksEntitlementsRequiredForSpecificBank.mkString(" and ") + " OR " + createUserCustomerLinksEntitlementsRequiredForAnyBank.mkString(" and ") + " entitlements are required."

    resourceDocs += ResourceDoc(
      createUserCustomerLinks,
      apiVersion,
      "createUserCustomerLinks",
      "POST",
      "/banks/BANK_ID/user_customer_links",
      "Create User Customer Link",
      s"""Link a User to a Customer
        |
        |${authenticationRequiredMessage(true)}
        |
        |$createUserCustomerLinksrequiredEntitlementsText
        |""",
      createUserCustomerLinkJson,
      userCustomerLinkJson,
      List(
        UserNotLoggedIn,
        InvalidBankIdFormat, 
        BankNotFound, 
        InvalidJsonFormat,
        CustomerNotFoundByCustomerId, 
        UserHasMissingRoles,
        CustomerAlreadyExistsForUser, 
        CreateUserCustomerLinksError,
        UnknownError
      ),
      List(apiTagCustomer, apiTagUser),
      Some(List(canCreateUserCustomerLink,canCreateUserCustomerLinkAtAnyBank)))

    // TODO
    // Allow multiple UserCustomerLinks per user (and bank)

    lazy val createUserCustomerLinks : OBPEndpoint = {
      case "banks" :: BankId(bankId):: "user_customer_links" :: Nil JsonPost json -> _ => {
        cc =>
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            _ <- tryo(assert(isValidID(bankId.value)))?~! ErrorMessages.InvalidBankIdFormat
            (bank, callContext) <- BankX(bankId, Some(cc)) ?~! BankNotFound
            postedData <- tryo{json.extract[CreateUserCustomerLinkJson]} ?~! ErrorMessages.InvalidJsonFormat
            _ <- booleanToBox(postedData.user_id.nonEmpty) ?~! "Field user_id is not defined in the posted json!"
            user <- UserX.findByUserId(postedData.user_id) ?~! ErrorMessages.UserNotFoundById
            _ <- booleanToBox(postedData.customer_id.nonEmpty) ?~! "Field customer_id is not defined in the posted json!"
            (customer, callContext) <- Connector.connector.vend.getCustomerByCustomerIdLegacy(postedData.customer_id, callContext) ?~! ErrorMessages.CustomerNotFoundByCustomerId
            _ <- NewStyle.function.hasAllEntitlements(bankId.value, u.userId, createUserCustomerLinksEntitlementsRequiredForSpecificBank,
                  createUserCustomerLinksEntitlementsRequiredForAnyBank, callContext)
            _ <- booleanToBox(customer.bankId == bank.bankId.value, s"Bank of the customer specified by the CUSTOMER_ID(${customer.bankId}) has to matches BANK_ID(${bank.bankId.value}) in URL")
            _ <- booleanToBox(UserCustomerLink.userCustomerLink.vend.getUserCustomerLink(postedData.user_id, postedData.customer_id).isEmpty == true) ?~! CustomerAlreadyExistsForUser
            userCustomerLink <- UserCustomerLink.userCustomerLink.vend.createUserCustomerLink(postedData.user_id, postedData.customer_id, new Date(), true) ?~! CreateUserCustomerLinksError
            _ <- Connector.connector.vend.UpdateUserAccoutViewsByUsername(user.name)
            _ <- Full(AuthUser.refreshUser(user, callContext))
            
          } yield {
            val successJson = Extraction.decompose(code.api.v2_0_0.JSONFactory200.createUserCustomerLinkJSON(userCustomerLink))
            successJsonResponse(successJson, 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      addEntitlement,
      apiVersion,
      "addEntitlement",
      "POST",
      "/users/USER_ID/entitlements",
      "Add Entitlement for a User",
      """Create Entitlement. Grant Role to User.
        |
        |Entitlements are used to grant System or Bank level roles to Users. (For Account level privileges, see Views)
        |
        |For a System level Role (.e.g CanGetAnyUser), set bank_id to an empty string i.e. "bank_id":""
        |
        |For a Bank level Role (e.g. CanCreateAccount), set bank_id to a valid value e.g. "bank_id":"my-bank-id"
        |
        |Authentication is required and the user needs to be a Super Admin. Super Admins are listed in the Props file.""",
      code.api.ResourceDocs1_4_0.SwaggerDefinitionsJSON.createEntitlementJSON,
      entitlementJSON,
      List(
        UserNotLoggedIn,
        UserNotFoundById,
        UserNotSuperAdmin,
        InvalidJsonFormat,
        IncorrectRoleName,
        EntitlementIsBankRole, 
        EntitlementIsSystemRole,
        EntitlementAlreadyExists,
        UnknownError
      ),
      List(apiTagRole, apiTagEntitlement, apiTagUser, apiTagNewStyle),
      Some(List(canCreateEntitlementAtOneBank,canCreateEntitlementAtAnyBank)))

    lazy val addEntitlement : OBPEndpoint = {
      //add access for specific user to a list of views
      case "users" :: userId :: "entitlements" :: Nil JsonPost json -> _ => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            (_, callContext) <- NewStyle.function.findByUserId(userId, callContext)
            failMsg = s"$InvalidJsonFormat The Json body should be the $CreateEntitlementJSON "
            postedData <- NewStyle.function.tryons(failMsg, 400, callContext) {
              json.extract[CreateEntitlementJSON]
            }
            role <- Future { tryo{valueOf(postedData.role_name)} } map {
              val msg = IncorrectRoleName + postedData.role_name + ". Possible roles are " + ApiRole.availableRoles.sorted.mkString(", ")
              x => unboxFullOrFail(x, callContext, msg)
            }
            _ <- Helper.booleanToFuture(failMsg = if (ApiRole.valueOf(postedData.role_name).requiresBankId) EntitlementIsBankRole else EntitlementIsSystemRole, cc=callContext) {
              ApiRole.valueOf(postedData.role_name).requiresBankId == postedData.bank_id.nonEmpty
            }
            requiredEntitlements = canCreateEntitlementAtOneBank :: canCreateEntitlementAtAnyBank :: Nil
            requiredEntitlementsTxt = UserNotSuperAdmin +" or" + UserHasMissingRoles + canCreateEntitlementAtOneBank + s" BankId(${postedData.bank_id})." + " or" + UserHasMissingRoles + canCreateEntitlementAtAnyBank
            _ <- if(isSuperAdmin(u.userId)) Future.successful(Full(Unit))
                  else NewStyle.function.hasAtLeastOneEntitlement(requiredEntitlementsTxt)(postedData.bank_id, u.userId, requiredEntitlements, callContext)

            _ <- Helper.booleanToFuture(failMsg = BankNotFound, cc=callContext) {
              postedData.bank_id.nonEmpty == false || BankX(BankId(postedData.bank_id), callContext).map(_._1).isEmpty == false
            }
            _ <- Helper.booleanToFuture(failMsg = EntitlementAlreadyExists, cc=callContext) {
              hasEntitlement(postedData.bank_id, userId, role) == false
            }
            addedEntitlement <- Future(Entitlement.entitlement.vend.addEntitlement(postedData.bank_id, userId, postedData.role_name)) map { unboxFull(_) }
          } yield {
            (JSONFactory200.createEntitlementJSON(addedEntitlement), HttpCode.`201`(callContext))
          }
      }
    }

    resourceDocs += ResourceDoc(
      getEntitlements,
      apiVersion,
      "getEntitlements",
      "GET",
      "/users/USER_ID/entitlements",
      "Get Entitlements for User",
      s"""
        |
        |${authenticationRequiredMessage(true)}
        |
        |
      """.stripMargin,
      emptyObjectJson,
      entitlementJSONs,
      List(UserNotLoggedIn, UserHasMissingRoles, UnknownError),
      List(apiTagRole, apiTagEntitlement, apiTagUser),
      Some(List(canGetEntitlementsForAnyUserAtAnyBank)))


    lazy val getEntitlements: OBPEndpoint = {
      case "users" :: userId :: "entitlements" :: Nil JsonGet _ => {
        cc =>
            for {
              u <- cc.user ?~ ErrorMessages.UserNotLoggedIn
              _ <- NewStyle.function.ownEntitlement("", u.userId, canGetEntitlementsForAnyUserAtAnyBank, cc.callContext)
                entitlements <- Entitlement.entitlement.vend.getEntitlementsByUserId(userId)
            }
            yield {
              var json = EntitlementJSONs(Nil)
              // Format the data as V2.0.0 json
              if (isSuperAdmin(userId)) {
                // If the user is SuperAdmin add it to the list
                json = addedSuperAdminEntitlementJson(entitlements)
                successJsonResponse(Extraction.decompose(json))
              } else {
                json = JSONFactory200.createEntitlementJSONs(entitlements)
              }
              // Return
              successJsonResponse(Extraction.decompose(json))
            }
      }
    }

    resourceDocs += ResourceDoc(
      deleteEntitlement,
      apiVersion,
      "deleteEntitlement",
      "DELETE",
      "/users/USER_ID/entitlement/ENTITLEMENT_ID",
      "Delete Entitlement",
      """Delete Entitlement specified by ENTITLEMENT_ID for an user specified by USER_ID
        |
        |Authentication is required and the user needs to be a Super Admin.
        |Super Admins are listed in the Props file.
        |
        |
      """.stripMargin,
      emptyObjectJson,
      emptyObjectJson,
      List(UserNotLoggedIn, UserHasMissingRoles, EntitlementNotFound, UnknownError),
      List(apiTagRole, apiTagUser, apiTagEntitlement, apiTagNewStyle))


    lazy val deleteEntitlement: OBPEndpoint = {
      case "users" :: userId :: "entitlement" :: entitlementId :: Nil JsonDelete _ => {
        cc =>
            for {
              (Full(u), callContext) <- authenticatedAccess(cc)
              _ <- NewStyle.function.hasEntitlement("", u.userId, canDeleteEntitlementAtAnyBank, cc.callContext)

                entitlement <- Future(Entitlement.entitlement.vend.getEntitlementById(entitlementId)) map {
                x => fullBoxOrException(x ~> APIFailureNewStyle(EntitlementNotFound, 404, callContext.map(_.toLight)))
              } map { unboxFull(_) }
              _ <- Helper.booleanToFuture(UserDoesNotHaveEntitlement, cc=callContext) { entitlement.userId == userId }
              deleted <- Future(Entitlement.entitlement.vend.deleteEntitlement(Some(entitlement))) map {
                x => fullBoxOrException(x ~> APIFailureNewStyle(EntitlementCannotBeDeleted, 404, callContext.map(_.toLight)))
              } map { unboxFull(_) }
            } yield (deleted, HttpCode.`204`(cc.callContext))
      }
    }


    resourceDocs += ResourceDoc(
      getAllEntitlements,
      apiVersion,
      "getAllEntitlements",
      "GET",
      "/entitlements",
      "Get all Entitlements",
      """
        |
        |Login is required.
        |
        |
      """.stripMargin,
      emptyObjectJson,
      entitlementJSONs,
      List(UserNotLoggedIn, UnknownError),
      List(apiTagRole, apiTagEntitlement, apiTagNewStyle))


    lazy val getAllEntitlements: OBPEndpoint = {
      case "entitlements" :: Nil JsonGet _ => {
        cc =>
          for {
            (Full(u), callContext) <- authenticatedAccess(cc)
            _ <- NewStyle.function.hasEntitlement("", u.userId, canGetEntitlementsForAnyUserAtAnyBank,callContext)

              entitlements <- Entitlement.entitlement.vend.getEntitlementsFuture() map {
              connectorEmptyResponse(_, callContext)
            }
          } yield {
            (JSONFactory200.createEntitlementJSONs(entitlements), callContext)
          }
      }
    }

    // TODO Put message into doc below if not enabled (but continue to show API Doc)
    resourceDocs += ResourceDoc(
        elasticSearchWarehouse,
        apiVersion,
        "elasticSearchWarehouse",
        "GET",
        "/search/warehouse",
        "Search Warehouse Data Via Elasticsearch",
        """
          |Search warehouse data via Elastic Search.
          |
          |Login is required.
          |
          |CanSearchWarehouse entitlement is required to search warehouse data!
          |
          |Send your email, name, project name and user_id to the admins to get access.
          |
          |Elastic (search) is used in the background. See links below for syntax.
          |
          |
          |parameters:
          |
          | esType  - elasticsearch type
          |
          | simple query:
          |
          | q       - plain_text_query
          |
          | df      - default field to search
          |
          | sort    - field to sort on
          |
          | size    - number of hits returned, default 10
          |
          | from    - show hits starting from
          |
          | json query:
          |
          | source  - JSON_query_(URL-escaped)
          |
          |
          |Example usage:
          |
          |GET /search/warehouse/q=findThis
          |
          |or:
          |
          |GET /search/warehouse/source={"query":{"query_string":{"query":"findThis"}}}
          |
          |
          |Note!!
          |
          |The whole JSON query string MUST be URL-encoded:
          |
          |* For {  use %7B
          |* For }  use %7D
          |* For : use %3A
          |* For " use %22
          |
          |etc..
          |
          |
          |
          |Only q, source and esType are passed to Elastic
          |
          |Elastic simple query: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-uri-request.html
          |
          |Elastic JSON query: https://www.elastic.co/guide/en/elasticsearch/reference/current/query-filter-context.html
          |
          |You can specify the esType thus: /search/warehouse/esType=type&q=a
          |
        """,
        emptyObjectJson,
        emptyObjectJson, //TODO what is output here?
        List(UserNotLoggedIn, BankNotFound, UserHasMissingRoles, UnknownError),
        List(apiTagSearchWarehouse),
        Some(List(canSearchWarehouse)))

    val esw = new elasticsearchWarehouse
    lazy val elasticSearchWarehouse: OBPEndpoint = {
      case "search" :: "warehouse" :: queryString :: Nil JsonGet _ => {
        cc =>
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            _ <- Entitlement.entitlement.vend.getEntitlement("", u.userId, ApiRole.CanSearchWarehouse.toString) ?~! {UserHasMissingRoles + CanSearchWarehouse}
          } yield {
            successJsonResponse(Extraction.decompose(esw.searchProxy(u.userId, queryString)))
          }
      }
    }

    // TODO Put message into doc below if not enabled (but continue to show API Doc)
    resourceDocs += ResourceDoc(
        elasticSearchMetrics,
        apiVersion,
        "elasticSearchMetrics",
        "GET",
        "/search/metrics",
        "Search API Metrics via Elasticsearch",
        """
          |Search the API calls made to this API instance via Elastic Search.
          |
          |Login is required.
          |
          |CanSearchMetrics entitlement is required to search metrics data.
          |
          |
          |parameters:
          |
          | esType  - elasticsearch type
          |
          | simple query:
          |
          | q       - plain_text_query
          |
          | df      - default field to search
          |
          | sort    - field to sort on
          |
          | size    - number of hits returned, default 10
          |
          | from    - show hits starting from
          |
          | json query:
          |
          | source  - JSON_query_(URL-escaped)
          |
          |
          |example usage:
          |
          | /search/metrics/q=findThis
          |
          |or:
          |
          | /search/metrics/source={"query":{"query_string":{"query":"findThis"}}}
          |
          |
          |Note!!
          |
          |The whole JSON query string MUST be URL-encoded:
          |
          |* For {  use %7B
          |* For }  use %7D
          |* For : use %3A
          |* For " use %22
          |
          |etc..
          |
          |
          |
          |Only q, source and esType are passed to Elastic
          |
          |Elastic simple query: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-uri-request.html
          |
          |Elastic JSON query: https://www.elastic.co/guide/en/elasticsearch/reference/current/query-filter-context.html
          |
          |
        """,
        emptyObjectJson,
        emptyObjectJson,
        List(UserNotLoggedIn, UserHasMissingRoles, UnknownError),
        List(apiTagMetric, apiTagApi),
        Some(List(canSearchMetrics)))

    val esm = new elasticsearchMetrics
    lazy val elasticSearchMetrics: OBPEndpoint = {
      case "search" :: "metrics" :: queryString :: Nil JsonGet _ => {
        cc =>
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            _ <- Entitlement.entitlement.vend.getEntitlement("", u.userId, ApiRole.CanSearchMetrics.toString) ?~! {UserHasMissingRoles + CanSearchMetrics}
          } yield {
            successJsonResponse(Extraction.decompose(esm.searchProxy(u.userId, queryString)))
          }
      }
    }


    resourceDocs += ResourceDoc(
      getCustomers,
      apiVersion,
      "getCustomers",
      "GET",
      "/users/current/customers",
      "Get all customers for logged in user",
      """Information about the currently authenticated user.
        |
        |Authentication via OAuth is required.""",
      emptyObjectJson,
      customersJsonV140,
      List(UserNotLoggedIn, UserCustomerLinksNotFoundForUser, UnknownError),
      List(apiTagPerson, apiTagCustomer))

    lazy val getCustomers : OBPEndpoint = {
      case "users" :: "current" :: "customers" :: Nil JsonGet _ => {
        cc => {
          for {
            u <- cc.user ?~! ErrorMessages.UserNotLoggedIn
            //(bank, callContext) <- Bank(bankId, Some(cc)) ?~! BankNotFound
            customers <- tryo{CustomerX.customerProvider.vend.getCustomersByUserId(u.userId)} ?~! UserCustomerLinksNotFoundForUser
          } yield {
            val json = JSONFactory1_4_0.createCustomersJson(customers)
            successJsonResponse(Extraction.decompose(json))
          }
        }
      }
    }
  }
}