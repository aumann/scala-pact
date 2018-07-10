package com.itv.scalapact

import com.itv.scalapact.ScalaPactVerify.ScalaPactVerifyFailed
import com.itv.scalapact.shared.Maps._
import com.itv.scalapact.shared.typeclasses._
import com.itv.scalapact.shared._
import com.itv.scalapactcore.message.{IMessageStubber, MessageStubber}
import com.itv.scalapact.shared.ColourOuput._

import scala.language.implicitConversions
import scala.util.Properties

object ScalaPactForger {

  implicit def toOption[A](a: A): Option[A] = Option(a)

  implicit def rulesToOptionalList(
      rules: ScalaPactForger.ScalaPactMatchingRules
  ): Option[List[ScalaPactForger.ScalaPactMatchingRule]] =
    Option(rules.rules)

  implicit val options: ScalaPactOptions = ScalaPactOptions.DefaultOptions

  object forgePact extends ForgePactElements {
    protected val strict: Boolean = false
  }

  object forgeStrictPact extends ForgePactElements {
    protected val strict: Boolean = true
  }

  case class PartialScalaPactMessage(description: String,
                                     providerState: Option[String],
                                     meta: Message.Metadata,
                                     matchingRules: Map[String, MatchingRule]) {

    def withRegex(key: String, regex: String): PartialScalaPactMessage =
      withMatchingRule(key, MatchingRule(Some("regex"), Some(regex), None))

    def withMatchingRule(key: String, value: MatchingRule): PartialScalaPactMessage =
      copy(matchingRules = matchingRules + (key -> value))

    def withProviderState(state: String): PartialScalaPactMessage =
      copy(providerState = Some(state))

    def withMeta(meta: Message.Metadata): PartialScalaPactMessage =
      copy(meta = meta)

    def withContent[T](value: T)(implicit format: IMessageFormat[T], iInferTypes: IInferTypes[T]): Message =
      Message(
        description,
        providerState,
        format.encode(value),
        meta,
        merge(iInferTypes.infer(value), matchingRules),
        format.contentType
      )

    private def normalizeKey(key: String): String =
      if (key.startsWith("$"))
        key
      else if (key.startsWith("."))
        "$" + key
      else
        "$." + key

    private def merge(lowPriorityRules: Map[String, MatchingRule],
                      highPriorityRules: Map[String, MatchingRule]): Map[String, MatchingRule] = {
      val normalizeHighPriorityRules = highPriorityRules.map { case (k, v) => normalizeKey(k) -> v }
      highPriorityRules ++ lowPriorityRules.filterKeys { k =>
        !normalizeHighPriorityRules.contains(normalizeKey(k))
      }
    }
  }

  sealed trait ForgePactElements {
    protected val strict: Boolean

    def between(consumer: String): ScalaPartialPact = new ScalaPartialPact(consumer)

    class ScalaPartialPact(consumer: String) {
      def and(provider: String): ScalaPactDescription = new ScalaPactDescription(consumer, provider, None, Nil, Nil)
    }

    class ScalaPactDescription(consumer: String,
                               provider: String,
                               sslContextName: Option[String],
                               interactions: List[ScalaPactInteraction],
                               messages: List[Message]) {

      /**
        * Adds a message example to the Pact. Messages should be created using the helper object 'message'
        *
        * @param message [ScalaPactMessage] definition
        * @return [ScalaPactDescription] to allow the builder to continue
        */
      def addMessage(message: Message): ScalaPactDescription =
        new ScalaPactDescription(consumer, provider, sslContextName, interactions, messages :+ message)

      /**
        * Adds interactions to the Pact. Interactions should be created using the helper object 'interaction'
        *
        * @param interaction [ScalaPactInteraction] definition
        * @return [ScalaPactDescription] to allow the builder to continue
        */
      def addInteraction(interaction: ScalaPactInteraction): ScalaPactDescription =
        new ScalaPactDescription(consumer, provider, sslContextName, interactions :+ interaction, messages)

      def addSslContextForServer(name: String): ScalaPactDescription =
        new ScalaPactDescription(consumer, provider, Some(name), interactions, messages)

      def runConsumerTest[F[_], A](test: ScalaPactMockConfig => A)(implicit options: ScalaPactOptions,
                                                                   sslContextMap: SslContextMap,
                                                                   pactReader: IPactReader,
                                                                   pactWriter: IPactWriter,
                                                                   httpClient: IScalaPactHttpClient[F],
                                                                   pactStubber: IPactStubber): A =
        ScalaPactMock.runConsumerIntegrationTest(strict)(
          scalaPactDescriptionFinal(options)
        )(test)

      def runMessageTests[A](test: IMessageStubber[A] => IMessageStubber[A])(
          implicit contractWriter: messageSpec.IContractWriter,
          pactReader: IPactReader
      ): List[A] = runMessageTests(MessageStubber.Config(strictMode = false))(test)

      def runStrictMessageTests[A](test: IMessageStubber[A] => IMessageStubber[A])(
          implicit contractWriter: messageSpec.IContractWriter,
          pactReader: IPactReader
      ): List[A] = runMessageTests(MessageStubber.Config(strictMode = true))(test)

      private def runMessageTests[A](config: MessageStubber.Config)(test: IMessageStubber[A] => IMessageStubber[A])(
          implicit contractWriter: messageSpec.IContractWriter,
          pactReader: IPactReader
      ): List[A] = {
        contractWriter.writeContract(scalaPactDescriptionFinal(options))
        val result = test(MessageStubber(this.messages, config))
        if (result.outcome.isSuccess)
          result.results
        else {
          PactLogger.error(result.outcome.renderAsString.red)
          throw new ScalaPactVerifyFailed
        }
      }

      private def scalaPactDescriptionFinal(options: ScalaPactOptions): ScalaPactDescriptionFinal =
        ScalaPactDescriptionFinal(
          consumer,
          provider,
          sslContextName,
          interactions.map(i => i.finalise),
          messages,
          options
        )

    }

  }

  object messageSpec {
    implicit def default(implicit options: ScalaPactOptions, pactWriter: IPactWriter): IContractWriter =
      IContractWriter()

    trait IContractWriter {
      def writeContract(scalaPactDescriptionFinal: ScalaPactDescriptionFinal): Unit
    }

    private object IContractWriter {
      def apply()(implicit options: ScalaPactOptions, pactWriter: IPactWriter): IContractWriter = new IContractWriter {

        def writeContract(scalaPactDescriptionFinal: ScalaPactDescriptionFinal) =
          if (options.writePactFiles) {
            ScalaPactContractWriter.writePactContracts(options.outputPath)(pactWriter)(
              scalaPactDescriptionFinal.withHeaderForSsl
            )
          }
      }
    }
  }

  object message {

    def description(desc: String): PartialScalaPactMessage =
      PartialScalaPactMessage(desc, None, Map.empty, Map.empty)
  }

  object interaction {
    def description(message: String): ScalaPactInteraction =
      new ScalaPactInteraction(message, None, None, ScalaPactRequest.default, ScalaPactResponse.default)
  }

  class ScalaPactInteraction(description: String,
                             providerState: Option[String],
                             sslContextName: Option[String],
                             request: ScalaPactRequest,
                             response: ScalaPactResponse) {
    def given(state: String): ScalaPactInteraction =
      new ScalaPactInteraction(description, Option(state), sslContextName, request, response)

    def withSsl(sslContextName: String): ScalaPactInteraction =
      new ScalaPactInteraction(description, providerState, Some(sslContextName), request, response)

    def uponReceiving(path: String): ScalaPactInteraction = uponReceiving(GET, path, None, Map.empty, None, None)

    def uponReceiving(method: ScalaPactMethod, path: String): ScalaPactInteraction =
      uponReceiving(method, path, None, Map.empty, None, None)

    def uponReceiving(method: ScalaPactMethod, path: String, query: Option[String]): ScalaPactInteraction =
      uponReceiving(method, path, query, Map.empty, None, None)

    def uponReceiving(method: ScalaPactMethod,
                      path: String,
                      query: Option[String],
                      headers: Map[String, String],
                      body: Option[String],
                      matchingRules: Option[List[ScalaPactMatchingRule]]): ScalaPactInteraction =
      new ScalaPactInteraction(
        description,
        providerState,
        sslContextName,
        ScalaPactRequest(method, path, query, headers, body, matchingRules),
        response
      )

    def willRespondWith(status: Int): ScalaPactInteraction = willRespondWith(status, Map.empty, None, None)

    def willRespondWith(status: Int, body: String): ScalaPactInteraction =
      willRespondWith(status, Map.empty, Option(body), None)

    def willRespondWith(status: Int, headers: Map[String, String], body: String): ScalaPactInteraction =
      willRespondWith(status, headers, Option(body), None)

    def willRespondWith(status: Int,
                        headers: Map[String, String],
                        body: Option[String],
                        matchingRules: Option[List[ScalaPactMatchingRule]]): ScalaPactInteraction =
      new ScalaPactInteraction(
        description,
        providerState,
        sslContextName,
        request,
        ScalaPactResponse(status, headers, body, matchingRules)
      )

    def finalise: ScalaPactInteractionFinal =
      ScalaPactInteractionFinal(description, providerState, sslContextName, request, response)
  }

  case class ScalaPactDescriptionFinal(consumer: String,
                                       provider: String,
                                       serverSslContextName: Option[String],
                                       interactions: List[ScalaPactInteractionFinal],
                                       messages: List[Message],
                                       options: ScalaPactOptions) {
    def withHeaderForSsl: ScalaPactDescriptionFinal =
      copy(
        interactions = interactions.map(
          i =>
            i.copy(
              request = i.request
                .copy(headers = i.request.headers addOpt (SslContextMap.sslContextHeaderName -> i.sslContextName))
          )
        )
      )
  }

  case class ScalaPactInteractionFinal(description: String,
                                       providerState: Option[String],
                                       sslContextName: Option[String],
                                       request: ScalaPactRequest,
                                       response: ScalaPactResponse)

  object ScalaPactRequest {
    val default: ScalaPactRequest = ScalaPactRequest(GET, "/", None, Map.empty, None, None)
  }

  case class ScalaPactRequest(method: ScalaPactMethod,
                              path: String,
                              query: Option[String],
                              headers: Map[String, String],
                              body: Option[String],
                              matchingRules: Option[List[ScalaPactMatchingRule]])

  sealed trait ScalaPactMatchingRule {
    val key: String
  }

  case class ScalaPactMatchingRuleRegex(key: String, regex: String) extends ScalaPactMatchingRule

  case class ScalaPactMatchingRuleType(key: String) extends ScalaPactMatchingRule

  case class ScalaPactMatchingRuleArrayMinLength(key: String, minimum: Int) extends ScalaPactMatchingRule

  case class ScalaPactMatchingRules(rules: List[ScalaPactMatchingRule]) {
    def ~>(newRules: ScalaPactMatchingRules): ScalaPactMatchingRules = ScalaPactMatchingRules(
      rules = rules ++ newRules.rules
    )
  }

  object headerRegexRule {
    def apply(key: String, regex: String): ScalaPactMatchingRules = ScalaPactMatchingRules(
      rules = List(ScalaPactMatchingRuleRegex("$.headers." + key, regex))
    )
  }

  object bodyRegexRule {
    def apply(key: String, regex: String): ScalaPactMatchingRules = ScalaPactMatchingRules(
      rules = List(ScalaPactMatchingRuleRegex("$.body." + key, regex))
    )
  }

  object bodyTypeRule {
    def apply(key: String): ScalaPactMatchingRules = ScalaPactMatchingRules(
      rules = List(ScalaPactMatchingRuleType("$.body." + key))
    )
  }

  object bodyArrayMinimumLengthRule {
    def apply(key: String, minimum: Int): ScalaPactMatchingRules = ScalaPactMatchingRules(
      rules = List(ScalaPactMatchingRuleArrayMinLength("$.body." + key, minimum))
    )
  }

  object ScalaPactResponse {
    val default: ScalaPactResponse = ScalaPactResponse(200, Map.empty, None, None)
  }

  case class ScalaPactResponse(status: Int,
                               headers: Map[String, String],
                               body: Option[String],
                               matchingRules: Option[List[ScalaPactMatchingRule]])

  object ScalaPactOptions {
    val DefaultOptions: ScalaPactOptions =
      ScalaPactOptions(writePactFiles = true, outputPath = Properties.envOrElse("pact.rootDir", "target/pacts"))
  }

  case class ScalaPactOptions(writePactFiles: Boolean, outputPath: String)

  sealed trait ScalaPactMethod {
    val method: String
  }

  case object GET extends ScalaPactMethod {
    val method = "GET"
  }

  case object PUT extends ScalaPactMethod {
    val method = "PUT"
  }

  case object POST extends ScalaPactMethod {
    val method = "POST"
  }

  case object DELETE extends ScalaPactMethod {
    val method = "DELETE"
  }

  case object OPTIONS extends ScalaPactMethod {
    val method = "OPTIONS"
  }

}
