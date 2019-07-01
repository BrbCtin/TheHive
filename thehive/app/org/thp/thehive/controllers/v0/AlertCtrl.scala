package org.thp.thehive.controllers.v0

import java.util.Base64

import scala.util.{Failure, Success, Try}

import play.api.Logger
import play.api.http.HttpErrorHandler
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.{Action, AnyContent, Results}

import gremlin.scala.Graph
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph._
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers.{EntryPoint, FString, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity, PagedResult}
import org.thp.scalligraph.query.{PropertyUpdater, Query}
import org.thp.thehive.dto.v0.{InputAlert, InputObservable}
import org.thp.thehive.models.{Alert, Permissions, RichCaseTemplate, RichObservable}
import org.thp.thehive.services._

@Singleton
class AlertCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    alertSrv: AlertSrv,
    caseTemplateSrv: CaseTemplateSrv,
    observableSrv: ObservableSrv,
    attachmentSrv: AttachmentSrv,
    val userSrv: UserSrv,
    val caseSrv: CaseSrv,
    errorHandler: HttpErrorHandler,
    val queryExecutor: TheHiveQueryExecutor
) extends QueryCtrl {
  import CaseConversion._
  import CustomFieldConversion._
  import AlertConversion._
  import ObservableConversion._

  lazy val logger = Logger(getClass)

  def create: Action[AnyContent] =
    entryPoint("create alert")
      .extract("alert", FieldsParser[InputAlert])
      .extract("caseTemplate", FieldsParser[String].optional.on("caseTemplate"))
      .extract("observables", FieldsParser[InputObservable].sequence.on("artifacts"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplateName: Option[String]  = request.body("caseTemplate")
        val inputAlert: InputAlert            = request.body("alert")
        val observables: Seq[InputObservable] = request.body("observables")
        for {
          caseTemplate <- caseTemplateName.fold[Try[Option[RichCaseTemplate]]](Success(None)) { ct =>
            caseTemplateSrv
              .get(ct)
              .visible
              .richCaseTemplate
              .getOrFail()
              .map(Some(_))
          }

          user         <- userSrv.getOrFail(request.userId)
          organisation <- userSrv.getOrganisation(user)
          customFields = inputAlert.customFieldValue.map(fromInputCustomField).toMap
          _               <- userSrv.current.can(Permissions.manageAlert).existsOrFail()
          richAlert       <- alertSrv.create(request.body("alert"), organisation, customFields, caseTemplate)
          richObservables <- observables.toTry(observable => importObservable(richAlert.alert, observable))
        } yield Results.Created((richAlert -> richObservables.flatten).toJson)
      }

  private def importObservable(alert: Alert with Entity, observable: InputObservable)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Seq[RichObservable]] = {
    val createdObservables = observable.dataType match {
      case "file" =>
        observable.data.map(_.split(';')).toTry {
          case Array(filename, contentType, value) =>
            val data = Base64.getDecoder.decode(value)
            attachmentSrv
              .create(filename, contentType, data)
              .flatMap(attachment => observableSrv.create(observable, attachment, Nil))
          case data =>
            Failure(InvalidFormatAttributeError("artifacts.data", "filename;contentType;base64value", Set.empty, FString(data.mkString(";"))))
        }
      case _ => observable.data.toTry(d => observableSrv.create(observable, d, Nil))
    }
    createdObservables.map(_.map { richObservable =>
      alertSrv.addObservable(alert, richObservable.observable)
      richObservable
    })
  }

  def get(alertId: String): Action[AnyContent] =
    entryPoint("get alert")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .visible
          .richAlert
          .getOrFail()
          .map { richAlert =>
            Results.Ok((richAlert -> alertSrv.get(richAlert.alert).observables.richObservable.toList()).toJson)
          }
      }

  def update(alertId: String): Action[AnyContent] =
    entryPoint("update alert")
      .extract("alert", FieldsParser.update("alert", alertProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("alert")
        alertSrv
          .update(_.get(alertId).can(Permissions.manageAlert), propertyUpdaters)
          .flatMap {
            case (alertSteps, _) =>
              alertSteps
                .richAlert
                .getOrFail()
                .map(richAlert => Results.Ok((richAlert -> alertSrv.get(richAlert.alert).observables.richObservable.toList()).toJson))
          }
      }

  def mergeWithCase(alertId: String, caseId: String) = ???

  def markAsRead(alertId: String): Action[AnyContent] =
    entryPoint("mark alert as read")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail()
          .map { _ =>
            alertSrv.markAsRead(alertId)
            Results.NoContent
          }
      }

  def markAsUnread(alertId: String): Action[AnyContent] =
    entryPoint("mark alert as unread")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail()
          .map { _ =>
            alertSrv.markAsUnread(alertId)
            Results.NoContent
          }
      }

  def createCase(alertId: String): Action[AnyContent] =
    entryPoint("create case from alert")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          (alert, organisation) <- alertSrv.get(alertId).alertUserOrganisation(Permissions.manageCase).getOrFail()
          richCase              <- alertSrv.createCase(alert, None, organisation)
        } yield Results.Created(richCase.toJson)
      }

  def followAlert(alertId: String): Action[AnyContent] =
    entryPoint("follow alert")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail()
          .map { _ =>
            alertSrv.followAlert(alertId)
            Results.NoContent
          }
      }

  def unfollowAlert(alertId: String): Action[AnyContent] =
    entryPoint("unfollow alert")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail()
          .map { _ =>
            alertSrv.unfollowAlert(alertId)
            Results.NoContent
          }
      }

  def stats: Action[AnyContent] =
    entryPoint("alert stats")
      .extract("query", statsParser("listAlert"))
      .authTransaction(db) { implicit request => graph =>
        val queries: Seq[Query] = request.body("query")
        val results = queries
          .map(query => queryExecutor.execute(query, graph, request.authContext).toJson)
          .foldLeft(JsObject.empty) {
            case (acc, o: JsObject) => acc ++ o
            case (acc, r) =>
              logger.warn(s"Invalid stats result: $r")
              acc
          }
        Success(Results.Ok(results))
      }

  def search: Action[AnyContent] =
    entryPoint("search alert")
      .extract("query", searchParser("listAlert"))
      .authTransaction(db) { implicit request => graph =>
        val query: Query = request.body("query")
        val result       = queryExecutor.execute(query, graph, request.authContext)
        val resp         = Results.Ok((result.toJson \ "result").as[JsValue])
        result.toOutput match {
          case PagedResult(_, Some(size)) => Success(resp.withHeaders("X-Total" -> size.toString))
          case _                          => Success(resp)
        }
      }
}