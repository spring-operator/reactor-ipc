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
package reactor.aeron.publisher;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.aeron.Context;
import reactor.aeron.subscriber.AeronServer;
import reactor.aeron.utils.AeronTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.test.TestSubscriber;
import reactor.core.util.ReactiveStateUtils;
import reactor.io.buffer.Buffer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * @author Anatoly Kadyshev
 */
public class AeronClientServerUnicastTest extends AeronClientServerCommonTest {

	@Override
	protected Context createContext(String name) {
		return Context.create().name(name)
				.senderChannel(senderChannel)
				.receiverChannel(AeronTestUtils.availableLocalhostChannel());
	}

	@Test
	public void testNextSignalIsReceivedByTwoPublishers() throws InterruptedException {
		AeronServer server = AeronServer.create(createContext("subscriber"));
		server.start(channel -> {
			channel.send(Flux.fromIterable(createBuffers(6)));
			return Mono.empty();
		});

		AeronClient client1 = new AeronClient(createContext("client1"));

		TestSubscriber<String> subscriber1 = new TestSubscriber<String>(0);
		client1.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber1);
			return Mono.never();
		});

		subscriber1.request(3);

		subscriber1.awaitAndAssertNextValues("1", "2", "3");

		System.out.println(ReactiveStateUtils.scan(server).toString());
		System.out.println(ReactiveStateUtils.scan(subscriber1).toString());

		AeronClient client2 = new AeronClient(createContext("client2")
				.receiverChannel(AeronTestUtils.availableLocalhostChannel()));

		TestSubscriber<String> subscriber2 = new TestSubscriber<String>(0);
		client2.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber2);
			return Mono.never();
		});


		subscriber2.request(6);

		subscriber2.awaitAndAssertNextValues("1", "2", "3", "4", "5", "6").assertComplete();

		System.out.println(ReactiveStateUtils.scan(server).toString());
		System.out.println(ReactiveStateUtils.scan(subscriber2).toString());
		System.out.println(ReactiveStateUtils.scan(subscriber1).toString());

		subscriber1.request(3);

		subscriber1.awaitAndAssertNextValues("4", "5", "6").assertComplete();

		System.out.println(ReactiveStateUtils.scan(server).toString());
	}

	@Test
	public void testSubscriptionCancellationTerminatesAeronFlux() throws InterruptedException {
		AeronServer server = AeronServer.create(createContext("subscriber").autoCancel(true));
		server.start(channel -> {
			channel.send(Flux.fromIterable(createBuffers(6)));
			return Mono.empty();
		});

		AeronClient client = new AeronClient(createContext("client").autoCancel(false));
		TestSubscriber<String> subscriber = new TestSubscriber<String>(0);
		client.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber);
			return Mono.never();
		});

		subscriber.request(3);
		subscriber.awaitAndAssertNextValues("1", "2", "3");

		subscriber.cancel();

		TestSubscriber.await(TIMEOUT, "client wasn't terminated", client::isTerminated);
	}

	private static class HangingOnCompleteSubscriber implements Subscriber<String> {

		CountDownLatch completeReceivedLatch = new CountDownLatch(1);

		CountDownLatch canReturnLatch = new CountDownLatch(1);

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Integer.MAX_VALUE);
		}

		@Override
		public void onNext(String s) {
		}

		@Override
		public void onError(Throwable t) {
		}

		@Override
		public void onComplete() {
			try {
				completeReceivedLatch.countDown();
				canReturnLatch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Test
	public void testPublisherCanConnectToATerminalButRunningSubscriber() throws InterruptedException {
		AeronServer server = AeronServer.create(createContext("subscriber"));
		server.start(channel -> {
			channel.send(Flux.fromIterable(createBuffers(3)));
			return Mono.empty();
		});

		AeronClient client = new AeronClient(createContext("client"));
		HangingOnCompleteSubscriber subscriber = new HangingOnCompleteSubscriber();
		client.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber);
			return Mono.never();
		});

		assertTrue(subscriber.completeReceivedLatch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS));

		AeronClient client2 = new AeronClient(createContext("client2"));

		TestSubscriber<String> subscriber2 = new TestSubscriber<String>(0);
		client2.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber2);
			return Mono.never();
		});

		subscriber2.request(3);

		subscriber2.awaitAndAssertNextValueCount(3).assertComplete();

		subscriber.canReturnLatch.countDown();
	}

	@Test
	public void testRequestAfterCompleteEventIsNoOp() {
		AeronServer server = AeronServer.create(createContext("subscriber"));
		server.start(channel -> {
			channel.send(Flux.fromIterable(createBuffers(3)));
			return Mono.empty();
		});

		AeronClient client = new AeronClient(createContext("client").autoCancel(false));
		TestSubscriber<String> subscriber = new TestSubscriber<>(0);
		client.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber);
			return Mono.never();
		});

		subscriber.request(3);

		subscriber.awaitAndAssertNextValues("1", "2", "3");
		subscriber.assertComplete();

		TestSubscriber.await(TIMEOUT, () -> "Publisher hasn't been terminated", client::isTerminated);

		subscriber.request(1);
	}

	@Test
	public void testRequestAfterErrorEventIsNoOp() throws InterruptedException {
		AeronServer aeronSubscriber = AeronServer.create(createContext("subscriber"));
		aeronSubscriber.start(channel -> {
			channel.send(Flux.<Buffer>error(new RuntimeException("Oops!")));
			return Mono.empty();
		});

		AeronClient client = new AeronClient(createContext("client").autoCancel(false));
		TestSubscriber<String> subscriber = new TestSubscriber<>(0);
		client.start(request -> {
			Buffer.bufferToString(request.receive()).subscribe(subscriber);
			return Mono.never();
		});


		subscriber.request(1);

		subscriber.await(TIMEOUT).assertError();

		TestSubscriber.await(TIMEOUT, () -> "Publisher hasn't been terminated", client::isTerminated);

		subscriber.request(1);
	}

}