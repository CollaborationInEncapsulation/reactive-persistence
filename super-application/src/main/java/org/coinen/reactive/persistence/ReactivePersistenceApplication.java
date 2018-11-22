package org.coinen.reactive.persistence;

import lombok.extern.slf4j.Slf4j;
import org.coinen.reactive.persistence.utils.AppSchedulers;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.result.view.Rendering;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static java.time.Duration.between;
import static java.time.Instant.now;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.resources;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@Slf4j
@SpringBootApplication
public class ReactivePersistenceApplication implements CommandLineRunner {
	private static final String IO_WORKER = "ioWorker";
	private static final String EXTERNAL_SERVICE = "http://localhost:9090";

	private final ThreadPoolExecutor executor = AppSchedulers.newExecutor(IO_WORKER, 4);
	private final Scheduler ioScheduler = Schedulers.fromExecutor(executor);

	private final HttpClient httpClient = HttpClient.newBuilder().build();
	private final WebClient webClient = WebClient.builder().build();

	private final AtomicInteger activeIncomingRequests = new AtomicInteger(0);

	public static void main(String[] args) {
		SpringApplication.run(ReactivePersistenceApplication.class, args);
	}

	@Bean
	public RouterFunction<?> routerFunction() {
		return RouterFunctions
			.route(
				GET("/"),
				request -> ok().render(
					"index",
					Rendering.view("index"))
			).andRoute(
				GET("/service/{study}/{region}"),
				request -> ok()
					.contentType(MediaType.APPLICATION_JSON)
					.body(processRequestBlocking(request), String.class)
			).andRoute(
				GET("/nio/service/{study}/{region}"),
				request -> ok()
					.contentType(MediaType.APPLICATION_JSON)
					.body(processRequestReactive(request), String.class)
            ).andRoute(
                GET("/status"),
                request -> ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(applicationStatus(), AppStatusDto.class)
			).andOther(
				resources("/**", new ClassPathResource("/static"))
			);
	}

    private Flux<AppStatusDto> applicationStatus() {
	    return Flux.combineLatest(
            Flux.interval(Duration.ofMillis(250)),
            webClient.get()
                .uri(URI.create(EXTERNAL_SERVICE + "/status"))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .flatMapMany(response -> response.bodyToFlux(ExternalServiceStatusDto.class)),
            (__, externalStatus) -> new AppStatusDto(
                executor.getMaximumPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                activeIncomingRequests.get(),
                externalStatus.getActiveRequests()
            ))
            .doOnError(e -> log.warn("Error on status", e));
    }

    private Mono<String> processRequestBlocking(ServerRequest request) {
		return Mono.fromCallable(
			() -> {
				Instant start = now();
				log.info("Starting external call");
				HttpRequest req = HttpRequest.newBuilder()
					.uri(externalServiceUri(request))
					.build();

				HttpResponse<String> response = httpClient
					.send(req, HttpResponse.BodyHandlers.ofString());
				log.info("External call finished in {}", between(start, now()));
				return response.body();
			})
			.publishOn(ioScheduler)
			.doOnSubscribe(s -> activeIncomingRequests.incrementAndGet())
			.doFinally(s -> activeIncomingRequests.decrementAndGet());
	}

	private Mono<String> processRequestReactive(ServerRequest request) {
		return webClient
			.get()
			.uri(externalServiceUri(request))
			.exchange()
			.flatMap(rsp -> rsp.bodyToMono(String.class))
            .doOnSubscribe(s -> activeIncomingRequests.incrementAndGet())
            .doFinally(s -> activeIncomingRequests.decrementAndGet());
	}

	@Override
	public void run(String... args) {
		// TODO: Expose as SSE endpoint & show on UI
		Flux.interval(Duration.ofSeconds(1))
			.doOnEach(i -> log.debug("[{} status] active req: {}, run/max: {}/{}, queued tasks: {}",
				IO_WORKER,
				activeIncomingRequests.get(),
				executor.getActiveCount(),
				executor.getMaximumPoolSize(),
				executor.getQueue().size()))
			.subscribe();
	}

	private URI externalServiceUri(ServerRequest request) {
		return URI.create(
			EXTERNAL_SERVICE +
				"/service/" +
				request.pathVariable("study") + "/" +
				request.pathVariable("region") +
				request.queryParam("timeout")
						.map(t -> "?timeout=" + t)
						.orElse("")
		);
	}
}