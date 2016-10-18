package fi.otavanopisto.restfulptv.server.statutorydescriptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.AccessTimeout;
import javax.ejb.Singleton;
import javax.ejb.Timeout;
import javax.ejb.Timer;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import fi.otavanopisto.ptv.client.ApiResponse;
import fi.otavanopisto.ptv.client.model.VmOpenApiGeneralDescription;
import fi.otavanopisto.restfulptv.server.PtvTranslator;
import fi.otavanopisto.restfulptv.server.ptv.PtvApi;
import fi.otavanopisto.restfulptv.server.rest.model.StatutoryDescription;
import fi.otavanopisto.restfulptv.server.schedulers.EntityUpdater;

@ApplicationScoped
@Singleton
@AccessTimeout (unit = TimeUnit.HOURS, value = 1l)
@SuppressWarnings("squid:S3306")
public class StatutoryDescriptionEntityUpdater extends EntityUpdater {

  private static final int TIMER_INTERVAL = 5000;

  @Inject
  private Logger logger;

  @Inject
  private PtvApi ptvApi;

  @Inject
  private StatutoryDescriptionCache statutoryDescriptionCache;

  @Inject
  private PtvTranslator ptvTranslator;

  @Resource
  private TimerService timerService;

  private boolean stopped;
  private List<String> queue;

  @PostConstruct
  public void init() {
    queue = Collections.synchronizedList(new ArrayList<>());
  }

  @Override
  public String getName() {
    return "statutoryDescriptions";
  }

  @Override
  public void startTimer() {
    startTimer(TIMER_INTERVAL);
  }

  private void startTimer(int duration) {
    stopped = false;
    TimerConfig timerConfig = new TimerConfig();
    timerConfig.setPersistent(false);
    timerService.createSingleActionTimer(duration, timerConfig);
  }

  @Override
  public void stopTimer() {
    stopped = true;
  }

  public void onStatutoryDescriptionIdUpdateRequest(@Observes StatutoryDescriptionIdUpdateRequest event) {
    if (!stopped) {
      if (event.isPriority()) {
        queue.remove(event.getId());
        queue.add(0, event.getId());
      } else {
        if (!queue.contains(event.getId())) {
          queue.add(event.getId());
        }
      }
    }
  }

  @Timeout
  public void timeout(Timer timer) {
    if (!stopped) {
      try {
        if (!queue.isEmpty()) {
          processEntity(queue.iterator().next());
        }
      } finally {
        startTimer(TIMER_INTERVAL);
      }

    }
  }

  private void processEntity(String entityId) {
    if (!queue.remove(entityId)) {
      logger.warning(String.format("Could not remove %s from queue", entityId));
    }

    ApiResponse<VmOpenApiGeneralDescription> response = ptvApi.getGeneralDescriptionApi()
        .apiGeneralDescriptionByIdGet(entityId);
    if (response.isOk()) {
      cacheResponse(entityId, response.getResponse());
    } else {
      logger.warning(String.format("Service %s caching failed on [%d] %s", entityId, response.getStatus(),
          response.getMessage()));
    }
  }

  private void cacheResponse(String entityId, VmOpenApiGeneralDescription ptvStatutoryDescription) {
    StatutoryDescription statutoryDescription = ptvTranslator.translateStatutoryDescription(ptvStatutoryDescription);
    if (statutoryDescription != null) {
      statutoryDescriptionCache.put(entityId, statutoryDescription);
    } else {
      logger.warning(String.format("Failed to translate ptvStatutoryDescription %s", ptvStatutoryDescription.getId()));
    }
  }

}
