/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.io.netty.nexus;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.logging.Level;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import reactor.core.flow.Loopback;
import reactor.core.publisher.Computations;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.TopicProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.TimedScheduler;
import reactor.core.scheduler.Timer;
import reactor.core.state.Introspectable;
import reactor.core.subscriber.SignalEmitter;
import reactor.core.util.Exceptions;
import reactor.core.util.Logger;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ReactiveStateUtils;
import reactor.core.util.WaitStrategy;
import reactor.io.ipc.Channel;
import reactor.io.ipc.ChannelHandler;
import reactor.io.netty.common.Peer;
import reactor.io.netty.http.HttpChannel;
import reactor.io.netty.http.HttpClient;
import reactor.io.netty.http.HttpInbound;
import reactor.io.netty.http.HttpServer;
import reactor.io.netty.tcp.TcpServer;

import static reactor.core.util.ReactiveStateUtils.property;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public final class Nexus extends Peer<ByteBuf, ByteBuf, Channel<ByteBuf, ByteBuf>>
		implements ChannelHandler<ByteBuf, ByteBuf, HttpChannel>, Loopback {

	/**
	 * Bind a new Console HTTP server to "loopback" on port {@literal 12012}. The default server
	 * implementation is scanned from the classpath on Class init. Support for Netty is provided
	 * as long as the relevant library dependencies are on the classpath. <p> To reply data on the active connection,
	 * {@link Channel#send} can subscribe to any passed {@link Publisher}. <p> Note
	 * that {@link reactor.core.state.Backpressurable#getCapacity} will be used to switch on/off a channel in auto-read /
	 * flush on write mode. If the capacity is Long.MAX_Value, write on flush and auto read will apply. Otherwise, data
	 * will be flushed every capacity batch size and read will pause when capacity number of elements have been
	 * dispatched. <p> Emitted channels will run on the same thread they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create() {
		return create(DEFAULT_BIND_ADDRESS);
	}

	/**
	 * Bind a new TCP server to "loopback" on the given port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of {@link
	 * Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * Publisher}. <p> Note that {@link reactor.core.state.Backpressurable#getCapacity} will be used to
	 * switch on/off a channel in auto-read / flush on write mode. If the capacity is Long.MAX_Value, write on flush and
	 * auto read will apply. Otherwise, data will be flushed every capacity batch size and read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on loopback
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create(int port) {
		return create(DEFAULT_BIND_ADDRESS, port);
	}

	/**
	 * Bind a new TCP server to the given bind address on port {@literal 12012}. The default server
	 * implementation is scanned from the classpath on Class init. Support for Netty is provided
	 * as long as the relevant library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of
	 * {@link Publisher} that will emit: - onNext {@link Channel} to consume data from -
	 * onComplete when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted
	 * {@link Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data
	 * on the active connection, {@link Channel#send} can subscribe to any passed {@link
	 * Publisher}. <p> Note that {@link reactor.core.state.Backpressurable#getCapacity} will be used to
	 * switch on/off a channel in auto-read / flush on write mode. If the capacity is Long.MAX_Value, write on flush and
	 * auto read will apply. Otherwise, data will be flushed every capacity batch size and read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the default port 12012
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create(String bindAddress) {
		return create(bindAddress, DEFAULT_PORT);
	}

	/**
	 * Bind a new TCP server to the given bind address and port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of {@link
	 * Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * Publisher}. <p> Note that {@link reactor.core.state.Backpressurable#getCapacity} will be used to
	 * switch on/off a channel in auto-read / flush on write mode. If the capacity is Long.MAX_Value, write on flush and
	 * auto read will apply. Otherwise, data will be flushed every capacity batch size and read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on the passed bind address
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the passed port
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create(final String bindAddress, final int port) {
		return create(HttpServer.create(bindAddress, port));
	}

	/**
	 * @param server
	 *
	 * @return
	 */
	public static Nexus create(HttpServer server) {

		Nexus nexus = new Nexus(server.getDefaultTimer(), server);
		log.info("Warping Nexus...");

		server.get(API_STREAM_URL, nexus);

		return nexus;
	}

	public static void main(String... args) throws Exception {
		log.info("Deploying Nexus... ");

		Nexus nexus = create();

		final CountDownLatch stopped = new CountDownLatch(1);

		nexus.startAndAwait();

		log.info("CTRL-C to return...");
		stopped.await();
	}

	final HttpServer                      server;
	final GraphEvent                      lastState;
	final SystemEvent                     lastSystemState;
	final FluxProcessor<Event, Event>     eventStream;
	final Scheduler                       group;
	final Function<Event, Event>          lastStateMerge;
	final TimedScheduler                  timer;
	final SignalEmitter<Publisher<Event>> cannons;

	static final AsciiString ALL = new AsciiString("*");

	@SuppressWarnings("unused")
	volatile FederatedClient[] federatedClients;
	long                 systemStatsPeriod;
	boolean              systemStats;
	boolean              logExtensionEnabled;
	NexusLoggerExtension logExtension;
	long websocketCapacity = 1L;

	Nexus(TimedScheduler defaultTimer, HttpServer server) {
		super(defaultTimer);
		this.server = server;
		this.eventStream = EmitterProcessor.create(false);
		this.lastStateMerge = new LastGraphStateMap();
		this.timer = Timer.create("create-poller");
		this.group = Computations.parallel("create", 1024, 1, null, null, false, WaitStrategy::blocking);

		FluxProcessor<Publisher<Event>, Publisher<Event>> cannons = EmitterProcessor.create();

		Flux.merge(cannons)
		    .subscribe(eventStream);

		this.cannons = cannons.connectEmitter();

		lastState = new GraphEvent(server.getListenAddress()
		                                 .toString(), ReactiveStateUtils.createGraph());

		lastSystemState = new SystemEvent(server.getListenAddress()
		                                        .toString());
	}

	@Override
	public Publisher<Void> apply(HttpChannel channel) {
		channel.responseHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN,  ALL);

		Flux<Event> eventStream = this.eventStream.publishOn(group)
		                                          .map(lastStateMerge);

		Publisher<Void> p;
		if (channel.isWebsocket()) {
			p = channel.upgradeToTextWebsocket()
			           .after(channel.send(federateAndEncode(channel, eventStream)));
		}
		else {
			p = channel.send(federateAndEncode(channel, eventStream));
		}

		channel.receiveString()
		       .consume(command -> {
			       int indexArg = command.indexOf("\n");
			       if (indexArg > 0) {
				       String action = command.substring(0, indexArg);
				       String arg = command.length() > indexArg ? command.substring(indexArg + 1) : null;
				       log.info("Received " + "[" + action + "]" + " " + "[" + arg + ']');
//					if(action.equals("pause") && !arg.isEmpty()){
//						((EmitterProcessor)Nexus.this.eventStream).pause();
//					}
//					else if(action.equals("resume") && !arg.isEmpty()){
//						((EmitterProcessor)Nexus.this.eventStream).resume();
//					}
			       }
		       });

		return p;
	}

	@Override
	public Object connectedInput() {
		return eventStream;
	}

	@Override
	public Object connectedOutput() {
		return server;
	}

	/**
	 * @return
	 */
	public final Nexus disableLogTail() {
		this.logExtensionEnabled = false;
		return this;
	}

	/**
	 * @param urls
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final Nexus federate(String... urls) {
		if (urls == null || urls.length == 0) {
			return this;
		}

		for (; ; ) {
			FederatedClient[] clients = federatedClients;

			int n;
			if (clients != null) {
				n = clients.length;
			}
			else {
				n = 0;
			}
			FederatedClient[] newClients = new FederatedClient[n + urls.length];

			if (n > 0) {
				System.arraycopy(clients, 0, newClients, 0, n);
			}

			for (int i = n; i < newClients.length; i++) {
				newClients[i] = new FederatedClient(urls[i - n]);
			}

			if (FEDERATED.compareAndSet(this, clients, newClients)) {
				break;
			}
		}

		return this;
	}

	/**
	 * @return
	 */
	public HttpServer getServer() {
		return server;
	}

	/**
	 * @return
	 */
	public final SignalEmitter<Object> metricCannon() {
		FluxProcessor<Object, Object> p = FluxProcessor.blocking();
		this.cannons.submit(p.map(new MetricMapper()));
		return p.connectEmitter();
	}

	/**
	 * @param o
	 * @param <E>
	 *
	 * @return
	 */
	public final <E> E monitor(E o) {
		return monitor(o, -1L);
	}

	/**
	 * @param o
	 * @param period
	 * @param <E>
	 *
	 * @return
	 */
	public final <E> E monitor(E o, long period) {
		return monitor(o, period, null);
	}

	/**
	 * @param o
	 * @param period
	 * @param unit
	 * @param <E>
	 *
	 * @return
	 */
	public final <E> E monitor(final E o, long period, TimeUnit unit) {

		final long _period = period > 0 ?  period : 400L;

		FluxProcessor<Object, Object> p = FluxProcessor.blocking();
		final SignalEmitter<Object> session = p.connectEmitter();
		log.info("State Monitoring Starting on " + ReactiveStateUtils.getName(o));
		timer.schedulePeriodically(() -> {
				if (!session.isCancelled()) {
					session.emit(ReactiveStateUtils.scan(o));
				}
				else {
					log.info("State Monitoring stopping on " + ReactiveStateUtils.getName(o));
					throw Exceptions.failWithCancel();
				}
		}, 0L, _period, unit != null ? unit : TimeUnit.MILLISECONDS);

		this.cannons.submit(p.map(new GraphMapper()));

		return o;
	}

	/**
	 * @see this#start(ChannelHandler)
	 */
	public final Mono<Void> start() throws InterruptedException {
		return start(null);
	}

	/**
	 * @see this#start(ChannelHandler)
	 */
	public final void startAndAwait() throws InterruptedException {
		start().get();
		InetSocketAddress addr = server.getListenAddress();
		log.info("Nexus Warped. Transmitting signal to troops under http://" + addr.getHostName() + ":" + addr.getPort() +
				API_STREAM_URL);
	}

	/**
	 * @return
	 */
	public final SignalEmitter<Object> streamCannon() {
		FluxProcessor<Object, Object> p = FluxProcessor.blocking();
		this.cannons.submit(p.map(new GraphMapper()));
		return p.connectEmitter();
	}

	/**
	 * @return
	 */
	public final Nexus useCapacity(long capacity) {
		this.websocketCapacity = capacity;
		return this;
	}

	/**
	 * @return
	 */
	public final Nexus withLogTail() {
		this.logExtensionEnabled = true;
		return this;
	}

	/**
	 * @return
	 */
	public final Nexus withSystemStats() {
		return withSystemStats(true, 1);
	}

	/**
	 * @param enabled
	 * @param period
	 *
	 * @return
	 */
	public final Nexus withSystemStats(boolean enabled, long period) {
		return withSystemStats(enabled, period, TimeUnit.SECONDS);
	}

	/**
	 * @param enabled
	 * @param period
	 * @param unit
	 *
	 * @return
	 */
	public final Nexus withSystemStats(boolean enabled, long period, TimeUnit unit) {
		this.systemStatsPeriod = unit == null || period < 1L ? 1000 : TimeUnit.MILLISECONDS.convert(period, unit);
		this.systemStats = enabled;
		return this;
	}

	@Override
	protected Mono<Void> doStart(ChannelHandler<ByteBuf, ByteBuf, Channel<ByteBuf, ByteBuf>> handler) {

		if (logExtensionEnabled) {
			FluxProcessor<Event, Event> p =
					TopicProcessor.share("create-log-sink", 256, WaitStrategy.blocking());
			cannons.submit(p);
			logExtension = new NexusLoggerExtension(server.getListenAddress()
			                                              .toString(), p.connectEmitter());

			//monitor(p);
			if (!Logger.enableExtension(logExtension)) {
				log.warn("Couldn't setup logger extension as one is already in place");
				logExtension = null;
			}
		}

		if (systemStats) {
			FluxProcessor<Event, Event> p = FluxProcessor.blocking();
			this.cannons.submit(p);
			final SignalEmitter<Event> session = p.connectEmitter();
			log.info("System Monitoring Starting");
			timer.schedulePeriodically(() -> {
					if (!session.isCancelled()) {
						session.submit(lastSystemState.scan());
					}
					else {
						log.info("System Monitoring Stopped");
						throw Exceptions.failWithCancel();
					}
			}, 0L, systemStatsPeriod, TimeUnit.MILLISECONDS);
		}

		return server.start();
	}

	@Override
	protected Mono<Void> doShutdown() {
		timer.shutdown();
		this.cannons.finish();
		this.eventStream.onComplete();
		if (logExtension != null) {
			Logger.disableExtension(logExtension);
			logExtension.logSink.finish();
			logExtension = null;
		}
		return server.shutdown();
	}

	Flux<? extends ByteBuf> federateAndEncode(HttpChannel c, Flux<Event> stream) {
		FederatedClient[] clients = federatedClients;
		if (clients == null || clients.length == 0) {
			return stream.map(BUFFER_STRING_FUNCTION)
			             .useCapacity(websocketCapacity);
		}
		Flux<ByteBuf> mergedUpstreams = Flux.merge(Flux.fromArray(clients)
		                                              .map(new FederatedMerger(c)));

		return Flux.merge(stream.map(BUFFER_STRING_FUNCTION), mergedUpstreams)
		           .useCapacity(websocketCapacity);
	}

	static class Event {

		final String nexusHost;

		public Event(String nexusHost) {
			this.nexusHost = nexusHost;
		}

		public String getNexusHost() {
			return nexusHost;
		}

		public String getType() {
			return getClass().getSimpleName();
		}
	}

	final static class GraphEvent extends Event {

		final ReactiveStateUtils.Graph graph;

		public GraphEvent(String name, ReactiveStateUtils.Graph graph) {
			super(name);
			this.graph = graph;
		}

		public ReactiveStateUtils.Graph getStreams() {
			return graph;
		}

		@Override
		public String toString() {
			return "{ " + property("streams", getStreams()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}
	}

	final static class RemovedGraphEvent extends Event {

		final Collection<String> ids;

		public RemovedGraphEvent(String name, Collection<String> ids) {
			super(name);
			this.ids = ids;
		}

		public Collection<String> getStreams() {
			return ids;
		}

		@Override
		public String toString() {
			return "{ " + property("streams", getStreams()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}
	}

	final static class LogEvent extends Event {

		final String message;
		final String category;
		final Level  level;
		final long   threadId;
		final String origin;
		final String data;
		final String kind;
		final long timestamp = System.currentTimeMillis();

		public LogEvent(String name, String category, Level level, String message, Object... args) {
			super(name);
			this.threadId = Thread.currentThread()
			                      .getId();
			this.message = message;
			this.level = level;
			this.category = category;
			if (args != null && args.length == 3) {
				this.kind = args[0].toString();
				this.data = args[1] != null ? args[1].toString() : null;
				this.origin = ReactiveStateUtils.getIdOrDefault(args[2]);
			}
			else {
				this.origin = null;
				this.kind = null;
				this.data = null;
			}
		}

		public String getCategory() {
			return category;
		}

		public String getData() {
			return data;
		}

		public String getKind() {
			return kind;
		}

		public Level getLevel() {
			return level;
		}

		public String getMessage() {
			return message;
		}

		public String getOrigin() {
			return origin;
		}

		public long getThreadId() {
			return threadId;
		}

		public long getTimestamp() {
			return timestamp;
		}

		@Override
		public String toString() {
			return "{ " + property("timestamp", getTimestamp()) +
					", " + property("level", getLevel().getName()) +
					", " + property("category", getCategory()) +
					(kind != null ? ", " + property("kind", getKind()) : "") +
					(origin != null ? ", " + property("origin", getOrigin()) : "") +
					(data != null ? ", " + property("data", getData()) : "") +
					", " + property("message", getMessage()) +
					", " + property("threadId", getThreadId()) +
					", " + property("type", getType()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}
	}

	final static class MetricEvent extends Event {

		public MetricEvent(String hostname) {
			super(hostname);
		}

		@Override
		public String toString() {
			return "{ " + property("nexusHost", getNexusHost()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					" }";
		}
	}

	final static class SystemEvent extends Event {

		final Map<Thread, ThreadState> threads = new WeakHashMap<>();
		public SystemEvent(String hostname) {
			super(hostname);
		}

		public JvmStats getJvmStats() {
			return jvmStats;
		}

		public Collection<ThreadState> getThreads() {
			return threads.values();
		}

		@Override
		public String toString() {
			return "{ " + property("jvmStats", getJvmStats()) +
					", " + property("threads", getThreads()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}

		SystemEvent scan() {
			int active = Thread.activeCount();
			Thread[] currentThreads = new Thread[active];
			int n = Thread.enumerate(currentThreads);

			for (int i = 0; i < n; i++) {
				if (!threads.containsKey(currentThreads[i])) {
					threads.put(currentThreads[i], new ThreadState(currentThreads[i]));
				}
			}
			return this;
		}

		final static class JvmStats {

			public int getActiveThreads() {
				return Thread.activeCount();
			}

			public int getAvailableProcessors() {
				return runtime.availableProcessors();
			}

			public long getFreeMemory() {
				return runtime.freeMemory(); //bytes
			}

			public long getMaxMemory() {
				return runtime.maxMemory(); //bytes
			}

			public long getUsedMemory() {
				return runtime.totalMemory(); //bytes
			}

			@Override
			public String toString() {
				return "{ " + property("freeMemory", getFreeMemory()) +
						", " + property("maxMemory", getMaxMemory()) +
						", " + property("usedMemory", getUsedMemory()) +
						", " + property("activeThreads", getActiveThreads()) +
						", " + property("availableProcessors", getAvailableProcessors()) + " }";
			}
		}

		final static class ThreadState {

			transient final Thread thread;

			public ThreadState(Thread thread) {
				this.thread = thread;
			}

			public long getContextHash() {
				if (thread.getContextClassLoader() != null) {
					return thread.getContextClassLoader()
					             .hashCode();
				}
				else {
					return -1;
				}
			}

			public long getId() {
				return thread.getId();
			}

			public String getName() {
				return thread.getName();
			}

			public int getPriority() {
				return thread.getPriority();
			}

			public Thread.State getState() {
				return thread.getState();
			}

			public String getThreadGroup() {
				ThreadGroup group = thread.getThreadGroup();
				return group != null ? thread.getThreadGroup()
				                             .getName() : null;
			}

			public boolean isAlive() {
				return thread.isAlive();
			}

			public boolean isDaemon() {
				return thread.isDaemon();
			}

			public boolean isInterrupted() {
				return thread.isInterrupted();
			}

			@Override
			public String toString() {
				return "{ " + property("id", getId()) +
						", " + property("name", getName()) +
						", " + property("alive", isAlive()) +
						", " + property("state", getState().name()) +
						(getThreadGroup() != null ? ", " + property("threadGroup", getThreadGroup()) : "") +
						(getContextHash() != -1 ? ", " + property("contextHash", getContextHash()) : "") +
						", " + property("interrupted", isInterrupted()) +
						", " + property("priority", getPriority()) +
						", " + property("daemon", isDaemon()) + " }";
			}
		}

		static final Runtime  runtime  = Runtime.getRuntime();
		static final JvmStats jvmStats = new JvmStats();
	}

	static class StringToBuffer implements Function<Event, ByteBuf> {

		@Override
		public ByteBuf apply(Event event) {
			try {
				return Unpooled.wrappedBuffer(event.toString()
				                     .getBytes("UTF-8"));
			}
			catch (UnsupportedEncodingException e) {
				throw Exceptions.propagate(e);
			}
		}
	}

	final static class NexusLoggerExtension implements Logger.Extension {

		final SignalEmitter<Event> logSink;
		final String                 hostname;

		public NexusLoggerExtension(String hostname, SignalEmitter<Event> logSink) {
			this.logSink = logSink;
			this.hostname = hostname;
		}

		@Override
		public void log(String category, Level level, String msg, Object... arguments) {
			String computed = Logger.format(msg, arguments);
			SignalEmitter.Emission emission;

			if (arguments != null && arguments.length == 3 && ReactiveStateUtils.isLogging(arguments[2])) {
				if (!(emission = logSink.emit(new LogEvent(hostname, category, level, computed, arguments))).isOk()) {
					//System.out.println(emission+ " "+computed);
				}
			}
			else {
				if (!(emission = logSink.emit(new LogEvent(hostname, category, level, computed))).isOk()) {
					//System.out.println(emission+ " "+computed);
				}
			}
		}
	}

	static final class FederatedMerger
			implements Function<FederatedClient, Publisher<ByteBuf>> {

		final HttpChannel c;

		public FederatedMerger(HttpChannel c) {
			this.c = c;
		}

		@Override
		public Publisher<ByteBuf> apply(FederatedClient o) {
			return o.client.ws(o.targetAPI)
			               .flatMap(HttpInbound::receive);
		}
	}

	static final class FederatedClient {

		final HttpClient client;
		final String     targetAPI;

		public FederatedClient(String targetAPI) {
			this.targetAPI = targetAPI;
			this.client = HttpClient.create();
		}
	}

	static final AtomicReferenceFieldUpdater<Nexus, FederatedClient[]> FEDERATED              =
			PlatformDependent.newAtomicReferenceFieldUpdater(Nexus.class, "federatedClients");
	static final Logger                                                log                    =
			Logger.getLogger(Nexus.class);
	static final String                                                API_STREAM_URL         =
			"/nexus/stream";
	static final Function<Event, ByteBuf>
	                                                                   BUFFER_STRING_FUNCTION =
			new StringToBuffer();

	class LastGraphStateMap implements Function<Event, Event>, Introspectable {

		@Override
		public Event apply(Event event) {
			if (GraphEvent.class.equals(event.getClass())) {
				lastState.graph.mergeWith(((GraphEvent) event).graph);
//				Collection<String> removed = lastState.graph.removeTerminatedNodes();
//
//				if(removed != null && !removed.isEmpty()){
//					return Flux.from(
//							Arrays.asList(lastState, new RemovedGraphEvent(server.getListenAddress().getHostName(), removed)));
//				}

				return lastState;
			}
			return event;
		}

		@Override
		public int getMode() {
			return 0;
		}

		@Override
		public String getName() {
			return "ScanIfGraphEvent";
		}
	}

	final class MetricMapper implements Function<Object, Event> {

		@Override
		public Event apply(Object o) {
			return new MetricEvent(server.getListenAddress()
			                             .toString());
		}

	}

	final class GraphMapper implements Function<Object, Event> {

		@Override
		public Event apply(Object o) {
			return new GraphEvent(server.getListenAddress()
			                            .toString(),
					ReactiveStateUtils.Graph.class.equals(o.getClass()) ? ((ReactiveStateUtils.Graph) o) :
							ReactiveStateUtils.scan(o));
		}

	}
}