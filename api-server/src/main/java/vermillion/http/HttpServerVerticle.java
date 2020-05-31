package vermillion.http;

import io.reactivex.functions.Consumer;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.JksOptions;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.core.http.HttpServerResponse;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import org.apache.commons.lang3.RandomStringUtils;
import vermillion.broker.reactivex.BrokerService;
import vermillion.database.reactivex.DbService;
import vermillion.throwables.BadRequestThrowable;
import vermillion.throwables.ConflictThrowable;
import vermillion.throwables.InternalErrorThrowable;
import vermillion.throwables.UnauthorisedThrowable;

public class HttpServerVerticle extends AbstractVerticle {
  public final Logger logger = LoggerFactory.getLogger(HttpServerVerticle.class);
  // HTTP Codes
  public final int OK = 200;
  public final int CREATED = 201;
  public final int ACCEPTED = 202;
  public final int BAD_REQUEST = 400;
  public final int FORBIDDEN = 403;
  public final int CONFLICT = 409;
  public final int INTERNAL_SERVER_ERROR = 500;

  // Service Proxies
  public DbService dbService;
  public BrokerService brokerService;

  @Override
  public void start(Promise<Void> startPromise) {
    logger.debug("In start");

    int port = 443;

    ConfigRetriever retriever = ConfigRetriever.create(vertx);

    dbService = vermillion.database.DbService.createProxy(vertx.getDelegate(), "db.queue");
    brokerService =
        vermillion.broker.BrokerService.createProxy(vertx.getDelegate(), "broker.queue");

    Router router = Router.router(vertx);

    router.route().handler(BodyHandler.create());

    router.post("/latest").handler(this::latest);

    retriever.rxGetConfig().subscribe((Consumer<JsonObject>) logger::debug);

    vertx
        .createHttpServer(
            new HttpServerOptions()
                .setSsl(true)
                .setCompressionSupported(true)
                .setKeyStoreOptions(
                    new JksOptions().setPath("my-keystore.jks").setPassword("password")))
        .requestHandler(router)
        .rxListen(port)
        .subscribe(
            s -> {
              logger.debug("Server started");
              startPromise.complete();
            },
            err -> {
              logger.debug("Could not start server. Cause=" + err.getMessage());
              startPromise.fail(err.getMessage());
            });
  }

  public void latest(RoutingContext context) {
    logger.debug("In latest API");
    HttpServerRequest request = context.request();
    HttpServerResponse response = context.response();

    JsonObject requestBody;

    try {
      requestBody = context.getBodyAsJson();
    } catch (Exception e) {
      apiFailure(context, new BadRequestThrowable("Body is not a valid JSON"));
      return;
    }

    logger.debug("Body=" + requestBody.encode());

    if (!requestBody.containsKey("id") && !requestBody.containsKey("options")) {
      apiFailure(context, new BadRequestThrowable("No id or options found in body"));
      return;
    }

    String resourceID = requestBody.getString("id");
    String options = requestBody.getString("options");

    // TODO: Use a template
    JsonObject queryJson =
        new JsonObject()
            .put(
                "query",
                new JsonObject().put("term", new JsonObject().put("id.keyword", resourceID)))
            .put("size", 1)
            .put(
                "sort",
                new JsonArray()
                    .add(new JsonObject().put("timestamp", new JsonObject().put("order", "desc"))));

    logger.debug("Query=" + queryJson.encode());

    dbService
        .rxRunQuery(queryJson)
        .subscribe(
            result -> {
              response.putHeader("content-type", "application/json").end(result.encode());
            });
  }

  public String genRandString(int len) {
    logger.debug("In genRandString");

    // Characters for generating apikeys
    String PASSWORD_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890-";
    String randStr =
        RandomStringUtils.random(
            len, 0, PASSWORD_CHARS.length(), true, true, PASSWORD_CHARS.toCharArray());

    logger.debug("Generated random string = " + randStr);

    return randStr;
  }

  public boolean isStringSafe(String resource) {
    logger.debug("In is_string_safe");

    logger.debug("resource=" + resource);

    boolean safe =
        (resource.length() - (resource.replaceAll("[^#-/a-zA-Z0-9-_.]+", "")).length()) == 0;

    logger.debug("Original resource name =" + resource);
    logger.debug("Replaced resource name =" + resource.replaceAll("[^#-/a-zA-Z0-9-_.]+", ""));
    return safe;
  }

  public void ok(HttpServerResponse resp) {
    if (!resp.closed()) {
      resp.setStatusCode(OK).end();
    }
  }

  public void accepted(HttpServerResponse resp) {
    if (!resp.closed()) {
      resp.setStatusCode(ACCEPTED).end();
    }
  }

  private void apiFailure(RoutingContext context, Throwable t) {
    logger.debug("In apifailure");
    logger.debug("Message=" + t.getMessage());
    if (t instanceof BadRequestThrowable) {
      context.response().setStatusCode(BAD_REQUEST).end(t.getMessage());
    } else if (t instanceof UnauthorisedThrowable) {
      context.response().setStatusCode(FORBIDDEN).end(t.getMessage());
    } else if (t instanceof ConflictThrowable) {
      context.response().setStatusCode(CONFLICT).end(t.getMessage());
    } else if (t instanceof InternalErrorThrowable) {
      context.response().setStatusCode(INTERNAL_SERVER_ERROR).end(t.getMessage());
    } else {
      context.fail(t);
    }
  }
}
