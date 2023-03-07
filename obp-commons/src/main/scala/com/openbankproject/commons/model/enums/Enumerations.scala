package com.openbankproject.commons.model.enums

import java.time.format.DateTimeFormatter

import com.openbankproject.commons.util.{EnumValue, JsonAble, OBPEnumeration}
import net.liftweb.common.Box
import net.liftweb.json.JsonAST.{JNothing, JString}
import net.liftweb.json.{Formats, JBool, JDouble, JInt, JValue}


sealed trait UserAttributeType extends EnumValue
object UserAttributeType extends OBPEnumeration[UserAttributeType]{
  object STRING         extends Value
  object INTEGER        extends Value
  object DOUBLE         extends Value
  object DATE_WITH_DAY  extends Value
}

sealed trait BankAttributeType extends EnumValue
object BankAttributeType extends OBPEnumeration[BankAttributeType]{
  object STRING         extends Value
  object INTEGER        extends Value
  object DOUBLE         extends Value
  object DATE_WITH_DAY  extends Value
}

sealed trait AccountAttributeType extends EnumValue
object AccountAttributeType extends OBPEnumeration[AccountAttributeType]{
  object STRING         extends Value
  object INTEGER        extends Value
  object DOUBLE         extends Value
  object DATE_WITH_DAY  extends Value
}

sealed trait ProductAttributeType extends EnumValue
object ProductAttributeType extends OBPEnumeration[ProductAttributeType]{
  object STRING        extends Value
  object INTEGER       extends Value
  object DOUBLE        extends Value
  object DATE_WITH_DAY extends Value
}

sealed trait CardAttributeType extends EnumValue
object CardAttributeType extends  OBPEnumeration[CardAttributeType]{
  object STRING        extends Value
  object INTEGER       extends Value
  object DOUBLE        extends Value
  object DATE_WITH_DAY extends Value
}

sealed trait CustomerAttributeType extends EnumValue
object CustomerAttributeType extends  OBPEnumeration[CustomerAttributeType]{
  object STRING        extends Value
  object INTEGER       extends Value
  object DOUBLE        extends Value
  object DATE_WITH_DAY extends Value
}

sealed trait TransactionAttributeType extends EnumValue
object TransactionAttributeType extends  OBPEnumeration[TransactionAttributeType]{
  object STRING        extends Value
  object INTEGER       extends Value
  object DOUBLE        extends Value
  object DATE_WITH_DAY extends Value
}

sealed trait TransactionRequestAttributeType extends EnumValue
object TransactionRequestAttributeType extends  OBPEnumeration[TransactionRequestAttributeType]{
  object STRING        extends Value
  object INTEGER       extends Value
  object DOUBLE        extends Value
  object DATE_WITH_DAY extends Value
}

//------api enumerations ----
sealed trait StrongCustomerAuthentication extends EnumValue
object StrongCustomerAuthentication extends OBPEnumeration[StrongCustomerAuthentication] {
  type SCA = Value
  object SMS extends Value
  object EMAIL extends Value
  object DUMMY extends Value
  object UNDEFINED extends Value

  //Berlin Group Standard:
//  - 'SMS_OTP': An SCA method, where an OTP linked to the transaction to be authorised is sent to the PSU through a SMS channel.
//    - 'CHIP_OTP': An SCA method, where an OTP is generated by a chip card, e.g. a TOP derived from an EMV cryptogram.
//    To contact the card, the PSU normally needs a (handheld) device.
//  With this device, the PSU either reads the challenging data through a visual interface like flickering or
//    the PSU types in the challenge through the device key pad.
//    The device then derives an OTP from the challenge data and displays the OTP to the PSU.
//    - 'PHOTO_OTP': An SCA method, where the challenge is a QR code or similar encoded visual data
//    which can be read in by a consumer device or specific mobile app.
//    The device resp. the specific app than derives an OTP from the visual challenge data and displays
//    the OTP to the PSU.
//    - 'PUSH_OTP': An OTP is pushed to a dedicated authentication APP and displayed to the PSU.
  object SMS_OTP extends Value
  object CHIP_OTP extends Value
  object PHOTO_OTP extends Value
  object PUSH_OTP extends Value
}

sealed trait StrongCustomerAuthenticationStatus extends EnumValue
object StrongCustomerAuthenticationStatus extends OBPEnumeration[StrongCustomerAuthenticationStatus] {
  type SCAStatus = Value
  
  //Berlin Group Standard:
  object received extends Value
  object psuIdentified extends Value
  object psuAuthenticated extends Value
  object scaMethodSelected extends Value
  object started extends Value
  object finalised extends Value
  object failed extends Value
  object exempted extends Value
}

sealed trait ChallengeType extends EnumValue
object ChallengeType extends OBPEnumeration[ChallengeType] {
  object OBP_TRANSACTION_REQUEST_CHALLENGE extends Value
  object OBP_CONSENT_CHALLENGE extends Value
  object BERLINGROUP_PAYMENT_CHALLENGE extends Value
  object BERLINGROUP_CONSENT_CHALLENGE extends Value
}

sealed trait PemCertificateRole extends EnumValue
object PemCertificateRole extends OBPEnumeration[PemCertificateRole] {
  type ROLE = Value
  object PSP_AS extends Value
  object PSP_IC extends Value
  object PSP_AI extends Value
  object PSP_PI extends Value
}
//------api enumerations end ----

sealed trait DynamicEntityFieldType extends EnumValue {
  val jValueType: Class[_]
  def isJValueValid(jValue: JValue): Boolean = jValueType.isInstance(jValue)
  def wrongTypeMsg = s"the value's type should be $this."
}
object DynamicEntityFieldType extends OBPEnumeration[DynamicEntityFieldType]{
  object number  extends Value{val jValueType = classOf[JDouble]}
  object integer extends Value{val jValueType = classOf[JInt]}
  object boolean extends Value{val jValueType = classOf[JBool]}
  object string  extends Value{
    val jValueType = classOf[JString]

    def isLengthValid(jValue: JValue, minLength: JValue, maxLength: JValue) = {
      if(minLength == JNothing && maxLength == JNothing) {
        true
      } else if(jValue == JNothing) {
        true
      } else {
        val value = jValue.asInstanceOf[JString].s
        val minLengthValue = if(minLength != JNothing) minLength.asInstanceOf[JInt].num.intValue() else 0
        val maxLengthValue = if(minLength != JNothing) maxLength.asInstanceOf[JInt].num.intValue() else Int.MaxValue
        minLengthValue <= value.size && value.size <= maxLengthValue
      }

    }
  }

 object DATE_WITH_DAY extends Value {
   val jValueType = classOf[JString]
   val dateFormat = "yyyy-MM-dd"
   override def isJValueValid(jValue: JValue): Boolean = {
     super.isJValueValid(jValue) && {
       val value = jValue.asInstanceOf[JString].s
       Box.tryo{
         DateTimeFormatter.ofPattern(dateFormat).parse(value)
       }.isDefined
     }
   }

   override def wrongTypeMsg: String = s"the value's type should be $this, format is $dateFormat."
 }
 //object array extends Value{val jValueType = classOf[JArray]}
 //object `object` extends Value{val jValueType = classOf[JObject]} //TODO in the future, we consider support nested type
}

/**
 * connector support operation type for DynamicEntity
 */
sealed trait DynamicEntityOperation extends EnumValue
object DynamicEntityOperation extends OBPEnumeration[DynamicEntityOperation] {
  object GET_ALL extends Value
  object GET_ONE extends Value
  object CREATE extends Value
  object UPDATE extends Value
  object DELETE extends Value
}

sealed trait ContentParam extends EnumValue
object ContentParam extends OBPEnumeration[ContentParam] {
  object DYNAMIC extends Value
  object STATIC extends Value
  object ALL extends Value
}

sealed trait AttributeType extends EnumValue
object AttributeType extends OBPEnumeration[AttributeType]{
  object STRING extends Value
  object INTEGER extends Value
  object DOUBLE extends Value
  object DATE_WITH_DAY extends Value
}

sealed trait AttributeCategory extends EnumValue
object AttributeCategory extends OBPEnumeration[AttributeCategory]{
  object Bank extends Value
  object Customer extends Value
  object Product extends Value
  object Account extends Value
  object Transaction extends Value
  object Card extends Value
  object TransactionRequest extends Value
}

object TransactionRequestStatus extends Enumeration {
  type TransactionRequestStatus = Value
  val INITIATED, PENDING, NEXT_CHALLENGE_PENDING, FAILED, COMPLETED, FORWARDED, REJECTED, CANCELLED, CANCELLATION_PENDING = Value
}

object AccountRoutingScheme extends Enumeration {
  type AccountRoutingScheme = Value
  val IBAN = Value
  val OBP = Value
}


object I18NResourceDocField extends Enumeration {
  type I18NResourceDocsField = Value
  val SUMMARY, DESCRIPTION, URL_PARAMETERS, JSON_REQUEST_BODY_FIELDS, JSON_RESPONSE_BODY_FIELDS = Value
}

//-------------------simple enum definition, just some sealed trait way, start-------------
trait SimpleEnum extends JsonAble {
  override def toJValue(implicit format: Formats): JValue = {
    val simpleName = this.getClass.getSimpleName.replaceFirst("\\$$", "")
    JString(simpleName)
  }
}

trait SimpleEnumCollection[+T] {
  def nameToValue: Map[String, T]

  def valueOf(value: String): T = nameToValue.collectFirst {
    case (name, enumValue) if name.equalsIgnoreCase(value) => enumValue
  }.getOrElse(throw new IllegalArgumentException ("Incorrect CardAction value: " + value))


  val availableValues = nameToValue.keys.toList
}
//-------------------simple enum definition, just some sealed trait way,   end-------------