package vermillion.http;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.core.http.HttpServerResponse;
import io.vertx.reactivex.ext.web.FileUpload;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.client.WebClient;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.ext.web.handler.StaticHandler;
import io.vertx.reactivex.redis.client.Redis;
import io.vertx.reactivex.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.validator.GenericValidator;
import vermillion.database.Queries;
import vermillion.database.reactivex.DbService;
import vermillion.throwables.BadRequestThrowable;
import vermillion.throwables.ConflictThrowable;
import vermillion.throwables.InternalErrorThrowable;
import vermillion.throwables.UnauthorisedThrowable;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

  // Auth server constants
  public final String AUTH_SERVER = System.getenv("AUTH_SERVER");
  public final String INTROSPECT_ENDPOINT = "/auth/v1/token/introspect";
  public final String WEBROOT = "webroot/";
  public final String PROVIDER_PATH = "/api-server/webroot/provider/";
  public final String AUTH_TLS_CERT_PATH = System.getenv("AUTH_TLS_CERT_PATH");
  public final String AUTH_TLS_CERT_PASSWORD = System.getenv("AUTH_TLS_CERT_PASSWORD");

  // Redis constants
  public final String REDIS_HOST = System.getenv("REDIS_HOSTNAME");

  /* Default port of redis. Port specified in the config file will
  	 not affect the default port to which redis is going to bind to
  */
  public final String REDIS_PORT = "6379";
  public final String REDIS_PASSWORD = System.getenv("REDIS_PASSWORD");

  // There are 16 DBs available. Using 1 as the default database number
  public final String DB_NUMBER = "1";
  // public final String CONNECTION_STR =
  //        "redis://:" + REDIS_PASSWORD + "@" + REDIS_HOST + ":" + REDIS_PORT + "/" + DB_NUMBER;

  public final String CONNECTION_STR =
      "redis://:" + REDIS_PASSWORD + "@" + REDIS_HOST + "/" + DB_NUMBER;

  public final int MAX_POOL_SIZE = 10;
  public final int MAX_WAITING_HANDLERS = 32;

  // Certificate constants
  public final String SSL_CERT_NAME = System.getenv("SSL_CERT_NAME");
  public final String SSL_CERT_PASSWORD = System.getenv("SSL_CERT_PASSWORD");

  // HTTPS port
  public final int HTTPS_PORT = 443;

  // Service Proxies
  public DbService dbService;
  public RedisOptions options;

  @Override
  public void start(Promise<Void> startPromise) {
    logger.debug("In start");
    logger.debug("auth server=" + AUTH_SERVER);
    logger.debug("Redis constr=" + CONNECTION_STR);

    dbService = vermillion.database.DbService.createProxy(vertx.getDelegate(), "db.queue");

    Router router = Router.router(vertx);

    router.route().handler(BodyHandler.create().setHandleFileUploads(true));

    router.post("/latest").handler(this::latest);
    router.post("/search").handler(this::search);

    router
        .routeWithRegex("\\/consumer\\/" + AUTH_SERVER + "\\/[^\\/]+\\/[0-9a-f]+\\/?.*")
        .handler(
            StaticHandler.create().setAllowRootFileSystemAccess(false).setDirectoryListing(true));
    router
        .routeWithRegex("\\/provider\\/public\\/?.*")
        .handler(
            StaticHandler.create().setAllowRootFileSystemAccess(false).setDirectoryListing(true));

    router.get("/download").handler(this::download);
    router.post("/publish").handler(this::publish);

    options =
        new RedisOptions()
            .setConnectionString(CONNECTION_STR)
            .setMaxPoolSize(MAX_POOL_SIZE)
            .setMaxWaitingHandlers(MAX_WAITING_HANDLERS);

    vertx
        .createHttpServer(
            new HttpServerOptions()
                .setSsl(true)
                .setCompressionSupported(true)
                .setKeyStoreOptions(
                    new JksOptions().setPath(SSL_CERT_NAME).setPassword(SSL_CERT_PASSWORD)))
        .requestHandler(router)
        .rxListen(HTTPS_PORT)
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

  // TODO: Check why using a custom conf file fails here
  public Single<RedisAPI> getRedisClient() {
    logger.debug("In get redis client");
    logger.debug("options=" + options.toJson().encodePrettily());
    return Redis.createClient(vertx, options).rxConnect().map(RedisAPI::api);
  }

  public Single<String> getValue(String key) {

    logger.debug("In getValue");

    return getRedisClient()
        .flatMapMaybe(
            redisAPI -> {
              logger.debug("Got redis client");
              return redisAPI.rxGet(key);
            })
        .map(
            value -> {
              logger.debug("Value=" + value.toString());
              return Optional.of(value);
            })
        .toSingle(Optional.empty())
        .map(value -> value.isPresent() ? value.get().toString() : "absent");
  }

  public Completable setValue(String key, String value) {

    logger.debug("In set value");
    ArrayList<String> list = new ArrayList<>();

    list.add(key);
    list.add(value);

    return getRedisClient()
        .flatMapCompletable(redisAPI -> Completable.fromMaybe(redisAPI.rxSet(list)));
  }

  // TODO: This can be a simple get request
  public void latest(RoutingContext context) {
    logger.debug("In latest API");
    HttpServerResponse response = context.response();

    JsonObject requestBody;

    try {
      requestBody = context.getBodyAsJson();
    } catch (Exception e) {
      apiFailure(context, new BadRequestThrowable("Body is not a valid JSON"));
      return;
    }

    logger.debug("Body=" + requestBody.encode());

    if (!requestBody.containsKey("id")) {
      apiFailure(context, new BadRequestThrowable("No id found in body"));
      return;
    }

    String resourceID = requestBody.getString("id");

    // Initialise queries object
    Queries queries = new Queries();

    JsonObject baseQuery = queries.getBaseQuery();
    JsonArray filterQuery = queries.getFilterQuery();
    JsonObject termQuery = queries.getTermQuery();

    termQuery.getJsonObject("term").put("id.keyword", resourceID);
    filterQuery.add(termQuery);
    baseQuery.getJsonObject("query").getJsonObject("bool").put("filter", filterQuery);

    JsonObject constructedQuery = queries.getLatestQuery(baseQuery);

    logger.debug(constructedQuery.encodePrettily());

    dbService
        .rxSearch(constructedQuery)
        .subscribe(
            result -> {
              response.putHeader("content-type", "application/json").end(result.encode());
            });
  }

  public void search(RoutingContext context) {
    // TODO: Convert all types of responses to JSON

    HttpServerRequest request = context.request();
    HttpServerResponse response = context.response();
    JsonObject requestBody;

    // Read the token if present
    String token = request.getParam("token");

    Queries queries = new Queries();

    JsonObject geoQuery = queries.getGeoQuery();
    JsonObject termQuery = queries.getTermQuery();
    JsonObject termsQuery = queries.getTermsQuery();
    JsonArray filterQuery = queries.getFilterQuery();
    JsonObject baseQuery = queries.getBaseQuery();

    String resourceIDstr = null;
    JsonArray resourceIDArray = null;

    try {
      requestBody = context.getBodyAsJson();
    } catch (Exception e) {
      apiFailure(context, new BadRequestThrowable("Body is not a valid JSON"));
      return;
    }

    logger.debug("Body=" + requestBody.encode());

    if (!requestBody.containsKey("id")) {
      apiFailure(context, new BadRequestThrowable("No id found in body"));
      return;
    }

    if (!requestBody.containsKey("geo_distance")
        && !requestBody.containsKey("time")
        && !requestBody.containsKey("attribute")) {
      apiFailure(context, new BadRequestThrowable("Invalid request"));
      return;
    }

    Object resourceIdObj = requestBody.getValue("id");

    if (!(resourceIdObj instanceof String) && !(resourceIdObj instanceof JsonArray)) {
      apiFailure(context, new BadRequestThrowable("Resource id is not valid"));
      return;
    }
    if (resourceIdObj instanceof JsonArray) {
      resourceIDArray = requestBody.getJsonArray("id");
      for (Object o : resourceIDArray) {
        if (!(o instanceof String)) {
          apiFailure(
              context, new BadRequestThrowable("Resource ID list should be a list of strings"));
          return;
        }
        if ("".equalsIgnoreCase(o.toString())) {
          apiFailure(context, new BadRequestThrowable("Resource ID is empty"));
          return;
        }
        if (!(((String) o).endsWith(".public")) && token == null) {
          apiFailure(context, new BadRequestThrowable("No token found in request"));
          return;
        }
      }
      termsQuery.getJsonObject("terms").put("id.keyword", resourceIDArray);
      filterQuery.add(termsQuery);
    } else {
      resourceIDstr = requestBody.getString("id");
      if ("".equalsIgnoreCase(resourceIDstr)) {
        apiFailure(context, new BadRequestThrowable("Resource ID is empty"));
        return;
      }
      if (!resourceIDstr.endsWith(".public") && token == null) {
        apiFailure(context, new BadRequestThrowable("No token found in request"));
        return;
      }
      termQuery.getJsonObject("term").put("id.keyword", resourceIDstr);
      filterQuery.add(termQuery);
    }

    // Geo Query
    if (requestBody.containsKey("geo_distance")) {
      Object geoDistanceObj = requestBody.getValue("geo_distance");

      if (!(geoDistanceObj instanceof JsonObject)) {
        apiFailure(context, new BadRequestThrowable("Geo distance is not a valid Json Object"));
        return;
      }

      JsonObject geoDistance = requestBody.getJsonObject("geo_distance");

      logger.debug("geo distance=" + geoDistance.encodePrettily());

      if (!geoDistance.containsKey("coordinates") || !geoDistance.containsKey("distance")) {
        apiFailure(
            context,
            new BadRequestThrowable("Geo distance does not contain coordinates and/or distance"));
        return;
      }

      Object distanceObj = geoDistance.getValue("distance");

      if (!(distanceObj instanceof String)) {
        apiFailure(context, new BadRequestThrowable("Distance is not a string"));
        return;
      }
      String distance = geoDistance.getString("distance");

      if (!distance.endsWith("m") && !distance.endsWith("M")) {
        apiFailure(
            context,
            new BadRequestThrowable(
                "Only metres are supported. Use the raw query interface for other units"));
        return;
      }

      String distanceQuantity = distance.substring(0, distance.length() - 1);

      logger.debug("Is a valid number ?" + NumberUtils.isCreatable(distanceQuantity));

      // If the number preceding m, km, cm etc is a valid number
      if (!NumberUtils.isCreatable(distanceQuantity)) {
        apiFailure(context, new BadRequestThrowable("Distance is not valid."));
        return;
      }

      Object coordinatesObj = geoDistance.getValue("coordinates");

      if (!(coordinatesObj instanceof JsonArray)) {
        apiFailure(context, new BadRequestThrowable("Coordinates is not a valid JsonArray"));
        return;
      }

      JsonArray coordinates = geoDistance.getJsonArray("coordinates");
      logger.debug("coordinates=" + coordinates.encodePrettily());

      logger.debug("coordinates size = " + coordinates.size());

      if (coordinates.size() != 2) {
        apiFailure(context, new BadRequestThrowable("Invalid coordinates"));
        return;
      }

      logger.debug(
          "Coordinates lat check = " + NumberUtils.isCreatable(coordinates.getValue(0).toString()));
      logger.debug(
          "Coordinates lon check = " + NumberUtils.isCreatable(coordinates.getValue(1).toString()));

      if (!NumberUtils.isCreatable(coordinates.getValue(0).toString())
          || !NumberUtils.isCreatable(coordinates.getValue(1).toString())) {
        apiFailure(context, new BadRequestThrowable("Coordinates should be valid numbers"));
        return;
      }

      double lat = coordinates.getDouble(0);
      double lon = coordinates.getDouble(1);

      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        apiFailure(context, new BadRequestThrowable("Invalid coordinates"));
        return;
      }

      geoQuery
          .getJsonObject("geo_distance")
          .put("distance", distance)
          .put("coordinates", coordinates);

      filterQuery = queries.getFilterQuery().add(geoQuery);
    }

    // Timeseries queries
    if (requestBody.containsKey("time")) {

      Object timeObj = requestBody.getValue("time");

      if (!(timeObj instanceof JsonObject)) {
        apiFailure(context, new BadRequestThrowable("Time is not a valid Json Object"));
        return;
      }

      JsonObject time = requestBody.getJsonObject("time");

      if (!time.containsKey("start") && !time.containsKey("end")) {
        apiFailure(context, new BadRequestThrowable("Start and end fields missing"));
        return;
      }

      Object startObj = time.getValue("start");
      Object endObj = time.getValue("end");

      if (!(startObj instanceof String) && !(endObj instanceof String)) {
        apiFailure(context, new BadRequestThrowable("Start and end objects are not strings"));
        return;
      }

      String start = time.getString("start");
      String end = time.getString("end");
      Locale locale = new Locale("English", "UK");

      if (!GenericValidator.isDate(start, locale) || !GenericValidator.isDate(end, locale)) {
        apiFailure(
            context, new BadRequestThrowable("Start and/or end strings are not valid dates"));
        return;
      }

      JsonObject timeQuery = queries.getTimeQuery();
      timeQuery.getJsonObject("range").getJsonObject("timestamp").put("gte", start).put("lte", end);

      filterQuery.add(timeQuery);
    }

    // Attribute query
    if (requestBody.containsKey("attribute")) {

      Object attributeObj = requestBody.getValue("attribute");

      if (!(attributeObj instanceof JsonObject)) {
        apiFailure(context, new BadRequestThrowable("Attribute is not a valid Json Object"));
        return;
      }
      JsonObject attribute = requestBody.getJsonObject("attribute");
      JsonObject attributeQuery = new JsonObject();

      if (!attribute.containsKey("term")) {
        apiFailure(context, new BadRequestThrowable("Attribute name is missing"));
        return;
      }

      Object attributeNameObj = attribute.getValue("term");

      if (!(attributeNameObj instanceof String)) {
        apiFailure(context, new BadRequestThrowable("Term is not a string"));
        return;
      }

      String attributeName = attribute.getString("term");

      if (!(attribute.containsKey("min") && attribute.containsKey("max"))
          == !(attribute.containsKey("term") && attribute.containsKey("value"))) {

        apiFailure(context, new BadRequestThrowable("Invalid attribute query"));
        return;
      }

      // TODO: Add a case where only min or max are provided. Not both
      // Case 1: When the attribute query is a number
      if (attribute.containsKey("min") && attribute.containsKey("max")) {

        Object minObj = attribute.getValue("min");
        Object maxObj = attribute.getValue("max");

        if (minObj instanceof String || maxObj instanceof String) {
          apiFailure(context, new BadRequestThrowable("Min and max values should be numbers"));
          return;
        }

        if (!NumberUtils.isCreatable(minObj.toString())
            || !NumberUtils.isCreatable(maxObj.toString())) {
          apiFailure(context, new BadRequestThrowable("Min and max values are not valid numbers"));
          return;
        }

        Double min = attribute.getDouble("min");
        Double max = attribute.getDouble("max");

        attributeQuery = queries.getRangeQuery();

        attributeQuery
            .getJsonObject("range")
            .put("data." + attributeName, new JsonObject().put("gte", min).put("lte", max));
        filterQuery.add(attributeQuery);

      } else {
        Object valueObj = attribute.getValue("value");
        if (!(valueObj instanceof String)) {
          apiFailure(context, new BadRequestThrowable("Value is not a valid string"));
          return;
        }

        String value = attribute.getString("value");
        attributeQuery = new Queries().getTermQuery();
        attributeQuery.getJsonObject("term").put("data." + attributeName + ".keyword", value);
        filterQuery.add(attributeQuery);
      }
    }

    baseQuery
        .put("size", 10000)
        .getJsonObject("query")
        .getJsonObject("bool")
        .put("filter", filterQuery);

    logger.debug(baseQuery.encodePrettily());

    // Trigger regular search function in three cases
    // 1. When the token provided is null: This is perfectly safe to do at this stage since secure
    // IDs are checked beforehand
    // 2. When token is provided but ID is a public ID
    // 3. When token is provided but ID is a list of public IDs
    // Don't know why anyone would do 2 & 3, but you never know
    if ((token == null)
        || (resourceIDstr != null && resourceIDstr.endsWith(".public"))
        || (resourceIDArray != null
            && resourceIDArray.stream()
                .map(Object::toString)
                .allMatch(s -> s.endsWith(".public")))) {
      dbService
          .rxSearch(baseQuery)
          .subscribe(
              result -> response.putHeader("content-type", "application/json").end(result.encode()),
              t -> apiFailure(context, t));
    } else {
      JsonArray requestedIDs = new JsonArray();

      if (resourceIDstr != null) {
        requestedIDs.add(resourceIDstr);
      } else {
        // Get only the secure IDs from the list of all IDs provided
        requestedIDs =
            resourceIDArray.stream()
                .map(Object::toString)
                .filter(s -> !s.endsWith(".public"))
                .collect(Collector.of(JsonArray::new, JsonArray::add, JsonArray::add));
        logger.debug("Requested IDs=" + requestedIDs.encodePrettily());
      }

      checkAuthorisation(token, requestedIDs)
          .andThen(Single.defer(() -> dbService.rxSecureSearch(baseQuery, token)))
          .subscribe(
              result -> response.putHeader("content-type", "application/json").end(result.encode()),
              t -> apiFailure(context, t));
    }
  }

  // TODO: If Id is provided, reroute to the specific file
  public void download(RoutingContext context) {

    HttpServerRequest request = context.request();

    // If token is valid for resources apart from secure 'files' then specify list of ids in the
    // request
    String token = request.getParam("token");
    String idParam = request.getParam("id");

    logger.info("token=" + token);

    if (token == null) {
      apiFailure(context, new BadRequestThrowable("No access token found in request"));
      return;
    }

    JsonArray requestedIds = new JsonArray();
    String basePath = PROVIDER_PATH + "secure/";

    if (idParam != null) {
      Arrays.asList(idParam.split(",")).forEach(requestedIds::add);
    }

    for (int i = 0; i < requestedIds.size(); i++) {
      if (requestedIds.getString(i).endsWith(".public")) {
        apiFailure(
            context,
            new BadRequestThrowable(
                "This API is for secure resources only. Use /provider/public endpoint to explore public data"));
        return;
      }
    }

    logger.debug("Requested IDs Json =" + requestedIds.encode());

    // Create consumer directory path if it does not exist

    new File(WEBROOT + "consumer/" + token).mkdirs();

    logger.debug("Created consumer subfolders");

    // TODO: Avoid duplication here
    if (idParam == null) {
      checkAuthorisation(token)
          // TODO: Rxify this further
          .flatMapCompletable(
              authorisedIds -> {
                logger.debug("Authorised IDs = " + authorisedIds.encode());

                for (int i = 0; i < authorisedIds.size(); i++) {
                  logger.debug("File=" + basePath + authorisedIds.getString(i));

                  if (Files.notExists(Paths.get(basePath + authorisedIds.getString(i)))) {
                    return Completable.error(
                        new UnauthorisedThrowable("Requested resource ID(s) is not present"));
                  }
                }

                for (int i = 0; i < authorisedIds.size(); i++) {

                  String resourceId = authorisedIds.getString(i);
                  // Get the actual file name on disk
                  String nakedId = resourceId.substring(resourceId.lastIndexOf('/') + 1);

                  Path consumerResourcePath =
                      Paths.get(WEBROOT + "consumer/" + token + "/" + nakedId);
                  Path providerResourcePath = Paths.get(basePath + resourceId);

                  // TODO: This could take a very long time for multiple large files
                  try {
                    Files.createSymbolicLink(consumerResourcePath, providerResourcePath);
                  } catch (FileAlreadyExistsException ignored) {

                  } catch (Exception e) {
                    return Completable.error(
                        new InternalErrorThrowable("Could not create symlinks"));
                  }
                }
                return Completable.complete();
              })
          .subscribe(
              () -> context.reroute("/consumer/" + token + "/"), t -> apiFailure(context, t));
    } else {
      checkAuthorisation(token, requestedIds)
          .andThen(
              Completable.fromCallable(
                  () -> {
                    logger.debug("Requested IDs = " + requestedIds.encode());
                    for (int i = 0; i < requestedIds.size(); i++) {
                      logger.debug("File=" + basePath + requestedIds.getString(i));
                      if (Files.notExists(Paths.get(basePath + requestedIds.getString(i)))) {
                        return Completable.error(
                            new UnauthorisedThrowable("Requested resource ID(s) is not present"));
                      }
                    }

                    for (int i = 0; i < requestedIds.size(); i++) {
                      String resourceId = requestedIds.getString(i);
                      String nakedId = resourceId.substring(resourceId.lastIndexOf('/') + 1);

                      Path consumerResourcePath =
                          Paths.get(WEBROOT + "consumer/" + token + "/" + nakedId);
                      Path providerResourcePath = Paths.get(basePath + resourceId);

                      // TODO: This could take a very long time for multiple large files
                      try {
                        Files.createSymbolicLink(consumerResourcePath, providerResourcePath);
                      } catch (FileAlreadyExistsException ignored) {

                      } catch (Exception e) {
                        return Completable.error(
                            new InternalErrorThrowable("Could not create symlinks"));
                      }
                    }
                    return Completable.complete();
                  }))
          .subscribe(
              () -> context.reroute("/consumer/" + token + "/"), t -> apiFailure(context, t));
    }
  }

  // Publish API for timeseries data as well as static files
  public void publish(RoutingContext context) {

    // TODO: Check scope before allowing publish - very imp
    logger.debug("In publish API");
    HttpServerRequest request = context.request();
    HttpServerResponse response = context.response();

    FileUpload file = null;
    FileUpload metadata = null;
    JsonObject metaJson = null;

    String fileName = null, resourceId, token;
    JsonObject requestBody = null;

    // TODO: Check for invalid IDs
    resourceId = request.getParam("id");
    token = request.getParam("token");

    if (resourceId == null) {
      apiFailure(context, new BadRequestThrowable("No resource ID found in request"));
      return;
    }
    if (token == null) {
      apiFailure(context, new BadRequestThrowable("No access token found in request"));
      return;
    }

    String[] splitId = resourceId.split("/");
    // TODO: Will this create problems if entire folders are shared?
    // TODO: What happens when there are nested folders? - better to accept catgeory as a field?
    String category = splitId[splitId.length - 2];

    JsonArray requestedIdList = new JsonArray().add(resourceId);

    HashMap<String, FileUpload> fileUploads = new HashMap<>();

    logger.debug("File uploads = " + context.fileUploads().size());
    logger.debug("Is empty = " + context.fileUploads().isEmpty());

    if (!context.fileUploads().isEmpty()) {
      context.fileUploads().forEach(f -> fileUploads.put(f.name(), f));
      logger.debug(fileUploads.toString());
    }

    if (fileUploads.size() > 0) {
      if (fileUploads.size() > 2 || !fileUploads.containsKey("file")) {
        apiFailure(
            context, new BadRequestThrowable("Too many files and/or missing 'file' parameter"));

        // Delete uploaded files if inputs are not as required
        fileUploads.forEach(
            (k, v) -> {
              try {
                Files.deleteIfExists(Paths.get(v.uploadedFileName()));
              } catch (IOException e) {
                e.printStackTrace();
              }
            });

        return;
      } else {
        file = fileUploads.get("file");

        if (fileUploads.containsKey("metadata")) {
          metadata = fileUploads.get("metadata");

          // TODO: Rxify this
          // TODO: File size could crash server. Need to handle this
          Buffer metaBuffer = vertx.fileSystem().readFileBlocking(metadata.uploadedFileName());

          try {
            metaJson = metaBuffer.toJsonObject();
          } catch (Exception e) {
            apiFailure(context, new BadRequestThrowable("Metadata is not a valid JSON"));
            return;
          }
          logger.debug("Metadata = " + metaJson.encode());
        } else {
          // Delete all other files except 'file' and 'metadata'
          fileUploads.forEach(
              (k, v) -> {
                if (!"file".equalsIgnoreCase(k)) {
                  try {
                    Files.deleteIfExists(Paths.get(v.uploadedFileName()));
                  } catch (IOException e) {
                    e.printStackTrace();
                  }
                }
              });
        }
      }
    }

    if (file != null) {
      fileName = file.uploadedFileName();

      String finalFileName = fileName;

      // If ID = domain/sha/rs.com/category/id, then create dir structure only until category
      // if it does not already exist
      String accessFolder =
          PROVIDER_PATH + (resourceId.endsWith(".public") ? "public/" : "secure/");

      String providerDirStructure =
          accessFolder + resourceId.substring(0, resourceId.lastIndexOf("/"));
      logger.debug("Provider dir structure=" + providerDirStructure);

      String providerFilePath = accessFolder + resourceId;
      logger.debug("Provider file path=" + providerFilePath);

      logger.debug("Source=" + finalFileName);
      logger.debug("Destination=" + providerFilePath);

      String fileLink = null;

      if (resourceId.endsWith(".public")) {
        fileLink = providerFilePath;
      } else {
        fileLink = "/download";
      }

      JsonObject dbJson =
          new JsonObject()
              .put("data", new JsonObject().put("link", fileLink))
              .put("timestamp", Clock.systemUTC().instant().toString())
              .put("id", resourceId)
              .put("category", category);

      if (metaJson != null) {
        logger.debug("Metadata is not null");
        // TODO: Cap size of metadata
        dbJson.getJsonObject("data").put("metadata", metaJson);
        try {
          logger.debug("Metadata path = " + metadata.uploadedFileName());
          Files.deleteIfExists(Paths.get(metadata.uploadedFileName()));
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      checkAuthorisation(token, requestedIdList)
          .andThen(
              Completable.defer(
                  () -> {
                    new File(providerDirStructure).mkdirs();
                    try {
                      Files.move(
                          Paths.get(finalFileName),
                          Paths.get(providerFilePath),
                          StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                      return Completable.error(new InternalErrorThrowable("Could not move files"));
                    }
                    return Completable.complete();
                  }))
          .andThen(dbService.rxInsert(dbJson))
          .subscribe(() -> response.setStatusCode(201).end("Ok"), t -> apiFailure(context, t));
      return;
    }
    // For timeseries data
    else {
      try {
        requestBody = context.getBodyAsJson();
      } catch (Exception e) {
        apiFailure(context, new BadRequestThrowable("Body is not a valid JSON"));
        return;
      }

      if (requestBody == null) {
        apiFailure(context, new BadRequestThrowable("Body is null"));
        return;
      }

      /* Data should be of the form
         {"data": object, "timestamp": timestamp, "coordinates": [lon, lat],
         "id" (populated by publish API): resource-id,
         "category"(populated by publish API): category}
      */

      Set<String> permittedFieldSet = new TreeSet<>();
      permittedFieldSet.add("data");
      permittedFieldSet.add("timestamp");
      permittedFieldSet.add("coordinates");

      if (!requestBody.containsKey("data")) {
        apiFailure(context, new BadRequestThrowable("No data field in body"));
        return;
      }
      if (!(requestBody.getValue("data") instanceof JsonObject)) {
        apiFailure(context, new BadRequestThrowable("Data field is not a JSON object"));
        return;
      }
      if (!permittedFieldSet.containsAll(requestBody.fieldNames())) {
        apiFailure(context, new BadRequestThrowable("Body contains unnecessary fields"));
        return;
      }

      if (!requestBody.containsKey("timestamp")) {
        requestBody.put("timestamp", Clock.systemUTC().instant().toString());
      }

      requestBody.put("id", resourceId);
      requestBody.put("category", category);
      requestBody.put("mime-type", "application/json");
    }

    JsonObject finalRequestBody = requestBody;

    checkAuthorisation(token, new JsonArray().add(resourceId))
        .andThen(Completable.defer(() -> dbService.rxInsert(finalRequestBody)))
        .subscribe(() -> response.setStatusCode(201).end(), t -> apiFailure(context, t));

    logger.debug("Filename = " + fileName);
  }

  // TODO: Handle server token
  // TODO: Handle regexes
  // Method that makes the HTTPS request to the auth server
  public Completable introspect(String token) {
    logger.debug("In introspect");
    JsonObject body = new JsonObject();
    body.put("token", token);

    WebClientOptions webClientOptions =
        new WebClientOptions()
            .setSsl(true)
            .setKeyStoreOptions(
                new JksOptions().setPath(AUTH_TLS_CERT_PATH).setPassword(AUTH_TLS_CERT_PASSWORD));

    if ("auth.local".equalsIgnoreCase(AUTH_SERVER)) {
      webClientOptions.setTrustAll(true);
    }

    WebClient client = WebClient.create(vertx, webClientOptions);

    return client
        .post(443, AUTH_SERVER, INTROSPECT_ENDPOINT)
        .ssl(true)
        .putHeader("content-type", "application/json")
        .rxSendJsonObject(body)
        .flatMapMaybe(
            response -> {
              if (response.statusCode() == 200) {
                return Maybe.just(response.bodyAsString());
              } else {
                logger.debug("Auth response=" + response.bodyAsString());
                return Maybe.empty();
              }
            })
        .map(Optional::of)
        .toSingle(Optional.empty())
        .flatMapCompletable(
            data ->
                (data.isPresent())
                    ? setValue(token, data.get())
                    : Completable.error(new UnauthorisedThrowable("Unauthorised")));
  }

  // Method that uses the redis cache for authorising requests.
  // Uses introspect if needed
  public Completable checkAuthorisation(String token, JsonArray requestedIds) {

    Set<String> requestedSet =
        IntStream.range(0, requestedIds.size())
            .mapToObj(requestedIds::getString)
            .collect(Collectors.toCollection(TreeSet::new));

    return getValue(token)
        .flatMapCompletable(
            cache -> "absent".equalsIgnoreCase(cache) ? introspect(token) : Completable.complete())
        // TODO: Avoid reading from cache again
        .andThen(Single.defer(() -> getValue(token)))
        .flatMapMaybe(
            result ->
                "absent".equalsIgnoreCase(result)
                    ? Maybe.empty()
                    : Maybe.just(new JsonObject(result).getJsonArray("request")))
        .map(Optional::of)
        .toSingle(Optional.empty())
        .flatMapMaybe(
            resultArray ->
                resultArray
                    .map(
                        authorisedIds ->
                            // TODO: In this case check for methods,
                            // body, API etc
                            Maybe.just(
                                IntStream.range(0, authorisedIds.size())
                                    .mapToObj(i -> authorisedIds.getJsonObject(i).getString("id"))
                                    .collect(Collectors.toCollection(TreeSet::new))))
                    .orElseGet(Maybe::empty))
        .map(Optional::of)
        .toSingle(Optional.empty())
        .flatMapCompletable(
            treeSet ->
                treeSet
                    .map(
                        authorisedSet ->
                            (authorisedSet.containsAll(requestedSet)
                                ? Completable.complete()
                                : Completable.error(
                                    new UnauthorisedThrowable("ACL does not match"))))
                    .orElseGet(() -> Completable.error(new UnauthorisedThrowable("Unauthorised"))));
  }

  public Single<JsonArray> checkAuthorisation(String token) {

    logger.debug("In check authorisation");

    return getValue(token)
        .flatMapCompletable(
            cache -> "absent".equalsIgnoreCase(cache) ? introspect(token) : Completable.complete())
        // TODO: Avoid reading from cache again
        .andThen(Single.defer(() -> getValue(token)))
        .flatMapMaybe(
            result ->
                "absent".equalsIgnoreCase(result)
                    ? Maybe.empty()
                    : Maybe.just(new JsonObject(result).getJsonArray("request")))
        .map(Optional::of)
        .toSingle(Optional.empty())
        .flatMapMaybe(
            resultArray ->
                resultArray
                    .map(
                        authorisedIds ->
                            // TODO: In this case check for methods,
                            // body, API etc
                            Maybe.just(
                                new JsonArray(
                                    IntStream.range(0, authorisedIds.size())
                                        .mapToObj(
                                            i -> authorisedIds.getJsonObject(i).getString("id"))
                                        .collect(Collectors.toList()))))
                    .orElseGet(Maybe::empty))
        .map(Optional::of)
        .toSingle(Optional.empty())
        .flatMap(
            result ->
                result
                    .map(Single::just)
                    .orElseGet(() -> Single.error(new UnauthorisedThrowable("Unauthorised"))));
  }

  public boolean isStringSafe(String resource) {
    logger.debug("In is_string_safe");

    logger.debug("resource=" + resource);

    boolean safe =
        (resource.length() - (resource.replaceAll("[^a-zA-Z0-9-_./@]+", "")).length()) == 0;

    logger.debug("Original resource name =" + resource);
    logger.debug("Replaced resource name =" + resource.replaceAll("[^a-zA-Z0-9-_./@]+", ""));
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
      context
          .response()
          .setStatusCode(BAD_REQUEST)
          .putHeader("content-type", "application/json")
          .end(t.getMessage());
    } else if (t instanceof UnauthorisedThrowable) {
      context
          .response()
          .setStatusCode(FORBIDDEN)
          .putHeader("content-type", "application/json")
          .end(t.getMessage());
    } else if (t instanceof ConflictThrowable) {
      context
          .response()
          .setStatusCode(CONFLICT)
          .putHeader("content-type", "application/json")
          .end(t.getMessage());
    } else if (t instanceof InternalErrorThrowable) {
      context
          .response()
          .setStatusCode(INTERNAL_SERVER_ERROR)
          .putHeader("content-type", "application/json")
          .end(t.getMessage());
    } else {
      context.fail(t);
    }
  }
}
