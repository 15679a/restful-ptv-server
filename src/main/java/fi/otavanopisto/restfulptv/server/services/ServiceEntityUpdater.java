package fi.otavanopisto.restfulptv.server.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.ejb.Timeout;
import javax.ejb.Timer;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.event.TransactionPhase;
import javax.inject.Inject;

import fi.otavanopisto.ptv.client.ApiResponse;
import fi.otavanopisto.ptv.client.model.IVmOpenApiService;
import fi.otavanopisto.restfulptv.server.PtvTranslator;
import fi.otavanopisto.restfulptv.server.ptv.PtvApi;
import fi.otavanopisto.restfulptv.server.rest.model.Service;
import fi.otavanopisto.restfulptv.server.schedulers.EntityUpdater;
import fi.otavanopisto.restfulptv.server.servicechannels.ServiceChannelResolver;
import fi.otavanopisto.restfulptv.server.servicechannels.ServiceChannelType;

@ApplicationScoped
@Singleton
@SuppressWarnings("squid:S3306")
@Lock(LockType.READ)
public class ServiceEntityUpdater extends EntityUpdater {

  private static final int TIMER_INTERVAL = 5000;

  @Inject
  private Logger logger;

  @Inject
  private PtvApi ptvApi;

  @Inject
  private ServiceChannelResolver serviceChannelResolver;

  @Inject
  private ServiceCache serviceCache;

  @Inject
  private ServiceChannelsCache serviceChannelsCache;

  @Inject
  private PtvTranslator ptvTranslator;

  @Resource
  private TimerService timerService;

  private boolean stopped;
  private boolean running;
  private List<String> queue;

  @PostConstruct
  public void init() {
    queue = Collections.synchronizedList(new ArrayList<>());
  }

  @Override
  public String getName() {
    return "services";
  }

  @Override
  public void startTimer() {
    running = false;
    startTimer(TIMER_INTERVAL);
  }

  @Override
  public void stopTimer() {
    stopped = true;
  }

  private void startTimer(int duration) {
    stopped = false;
    TimerConfig timerConfig = new TimerConfig();
    timerConfig.setPersistent(false);
    timerService.createSingleActionTimer(duration, timerConfig);
  }

  public void onServiceIdUpdateRequest(@Observes (during = TransactionPhase.AFTER_COMPLETION) ServiceIdUpdateRequest event) {
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
      if (running) {
        return;
      }

      try {
        running = true;
        if (!queue.isEmpty()) {
          processEntity(queue.iterator().next());
        }
      } finally {
        running = false;
        startTimer(TIMER_INTERVAL);
      }

    }
  }

  private void processEntity(String entityId) {
    if (!queue.remove(entityId)) {
      logger.warning(String.format("Could not remove %s from queue", entityId));
    }

    ApiResponse<IVmOpenApiService> response = ptvApi.getServiceApi().apiServiceByIdGet(entityId);
    if (response.isOk()) {
      cacheResponse(entityId, response.getResponse());
    } else {
      logger.warning(String.format("Service %s caching failed on [%d] %s", entityId, response.getStatus(), response.getMessage()));
    }
  }

  private void cacheResponse(String entityId, IVmOpenApiService ptvService) {
    Service service = ptvTranslator.translateService(ptvService);
    if (service != null) {
      serviceChannelsCache.put(ptvService.getId(), resolveServiceChannelIds(ptvService));
      serviceCache.put(entityId, service);
    } else {
      logger.warning(String.format("Failed to translate ptvService %s", ptvService.getId()));
    }
  }

  private ServiceChannelIds resolveServiceChannelIds(IVmOpenApiService ptvService) {
    ServiceChannelIds channelIds = new ServiceChannelIds();

    for (String channelId : ptvService.getServiceChannels()) {
      ServiceChannelType serviceChannelType = serviceChannelResolver.resolveServiceChannelTupe(channelId);
      if (serviceChannelType != null) {
        switch (serviceChannelType) {
        case ELECTRONIC_CHANNEL:
          channelIds.getElectricChannels().add(channelId);
          break;
        case SERVICE_LOCATION:
          channelIds.getLocationServiceChannels().add(channelId);
          break;
        case PRINTABLE_FORM:
          channelIds.getPrintableFormChannels().add(channelId);
          break;
        case PHONE:
          channelIds.getPhoneChannels().add(channelId);
          break;
        case WEB_PAGE:
          channelIds.getWebPageChannels().add(channelId);
          break;
        default:
          logger.log(Level.SEVERE, String.format("Unknown service channel type %s", serviceChannelType));
          break;
        }
      }
    }

    return channelIds;
  }

}
