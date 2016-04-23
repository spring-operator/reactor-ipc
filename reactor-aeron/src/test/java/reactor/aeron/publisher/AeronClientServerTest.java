package reactor.aeron.publisher;


import org.junit.Ignore;
import org.junit.Test;
import reactor.aeron.Context;
import reactor.aeron.subscriber.AeronServer;
import reactor.aeron.utils.AeronTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.test.TestSubscriber;
import reactor.io.buffer.Buffer;

public class AeronClientServerTest {

    final String senderChannel = AeronTestUtils.availableLocalhostChannel();

    protected Context createContext(String name) {
        return Context.create().name(name)
                .senderChannel(senderChannel)
                .receiverChannel(AeronTestUtils.availableLocalhostChannel());
    }

    @Test
    @Ignore
    public void test() {
        Mono<Void> serverStarted = AeronServer.create(createContext("server")).start(request ->
                request.send(Flux.just(Buffer.wrap("Live"),
                        Buffer.wrap("Hard"),
                        Buffer.wrap("Die"),
                        Buffer.wrap("Harder"),
                        Buffer.wrap("Extra")))
        );
        serverStarted.get();

        TestSubscriber<Buffer> subscriber = new TestSubscriber<>(0);

        Context clientContext = createContext("client").receiverChannel(AeronTestUtils.availableLocalhostChannel());
        Mono<Void> clientStarted = AeronClient.listenOn(clientContext).start(connection -> {
            connection.receive().subscribe(subscriber);
            return Flux.never();
        });
        clientStarted.get();

        subscriber.request(1);

        subscriber.awaitAndAssertNextValues(Buffer.wrap("Live"));
    }

}