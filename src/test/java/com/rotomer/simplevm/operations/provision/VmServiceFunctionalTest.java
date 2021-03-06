package com.rotomer.simplevm.operations.provision;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.rotomer.simplevm.messages.*;
import com.rotomer.simplevm.services.vm.di.VmServiceModule;
import com.rotomer.simplevm.sqs.SqsListener;
import com.rotomer.simplevm.sqs.SqsSender;
import com.rotomer.simplevm.sqs.SqsSettings;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.rotomer.simplevm.operations.provision.EmbeddedSqsTestFixture.*;
import static com.rotomer.simplevm.utils.IdGenerator.nextId;
import static com.rotomer.simplevm.utils.ProtobufEncoderDecoder.decodeMessageBase64;
import static com.rotomer.simplevm.utils.ProtobufEncoderDecoder.encodeMessageBase64;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.junit.Assert.assertEquals;

public class VmServiceFunctionalTest {

    private final static Config _config = ConfigFactory.parseString(
            "simplevm {\n" +
                    "vm-service {\n" +
                    "sqs {\n" +
                    "sqs-aws-service-endpoint = " + "\"" + SQS_AWS_SERVICE_ENDPOINT + "\"\n" +
                    "aws-region = " + AWS_REGION + "\n" +
                    "}\n" +
                    "listener {\n" +
                    "wait-time-seconds = 5\n" +
                    "queue-url = \"" + REQUEST_QUEUE_URL + "\"\n" +
                    "}\n" +
                    "response {\n" +
                    "queue-url = \"" + RESPONSE_QUEUE_URL + "\"\n" +
                    "}\n" +
                    "}\n" +
                    "}");

    private static SqsSender _sqsSender;
    private static SqsMessageReceiver _sqsMessageReceiver;
    private static EmbeddedSqsTestFixture _embeddedSqsTestFixture;
    private static SqsListener _unitUnderTest;

    @BeforeClass
    public static void setUp() {
        _embeddedSqsTestFixture = new EmbeddedSqsTestFixture();
        _embeddedSqsTestFixture.start();

        final SqsSettings sqsSettings = SqsSettings.fromConfig(_config.getConfig("simplevm.vm-service.sqs"));
        _sqsSender = new SqsSender(sqsSettings, AWS_CREDENTIALS_PROVIDER);
        _sqsMessageReceiver = new SqsMessageReceiver(sqsSettings, AWS_CREDENTIALS_PROVIDER);

        _unitUnderTest = startUnitUnderTest();
    }

    @AfterClass
    public static void tearDown() {
        _embeddedSqsTestFixture.close();
    }

    private static SqsListener startUnitUnderTest() {
        final Injector injector = Guice.createInjector(new VmServiceModule(_config, AWS_CREDENTIALS_PROVIDER));
        _unitUnderTest = injector.getInstance(SqsListener.class);

        newSingleThreadExecutor().submit(_unitUnderTest::start);

        return _unitUnderTest;
    }

    @Test
    public void testProvisionVm() throws InvalidProtocolBufferException {
        // arrange:
        final ProvisionVmCommand provisionVmCommand = ProvisionVmCommand.newBuilder()
                .setId(nextId())
                .setRegion(Region.US)
                .setVmSpec(VmSpec.newBuilder()
                        .setCpuCores(2)
                        .setGbRam(4))
                .build();
        final Any anyMessage = Any.pack(provisionVmCommand);
        final VmCommandEnvelope envelope = VmCommandEnvelope.newBuilder()
                .setInnerMessage(anyMessage)
                .setVmId(provisionVmCommand.getId())
                .build();
        final String encodedCommand = encodeMessageBase64(envelope);

        // act:
        _sqsSender.sendMessage(REQUEST_QUEUE_URL, encodedCommand);
        final String encodedResponse = _sqsMessageReceiver.receiveSingleMessage(RESPONSE_QUEUE_URL);

        // assert:
        final Any anyResponseMessage = decodeMessageBase64(encodedResponse, Any.newBuilder())
                .build();
        final VmProvisionedEvent actualResponse = anyResponseMessage.unpack(VmProvisionedEvent.class);
        assertEquals(provisionVmCommand, actualResponse.getCommand());
    }

    @Test
    public void testEditSpec() throws InvalidProtocolBufferException {
        // arrange:
        final EditSpecCommand editSpecCommand = EditSpecCommand.newBuilder()
                .setId(nextId())
                .setVmId(nextId())
                .setVmSpec(VmSpec.newBuilder()
                        .setCpuCores(2)
                        .setGbRam(4))
                .build();
        final Any anyMessage = Any.pack(editSpecCommand);
        final VmCommandEnvelope envelope = VmCommandEnvelope.newBuilder()
                .setInnerMessage(anyMessage)
                .setVmId(editSpecCommand.getId())
                .build();
        final String encodedCommand = encodeMessageBase64(envelope);

        // act:
        _sqsSender.sendMessage(REQUEST_QUEUE_URL, encodedCommand);
        final String encodedResponse = _sqsMessageReceiver.receiveSingleMessage(RESPONSE_QUEUE_URL);

        // assert:
        final Any anyResponseMessage = decodeMessageBase64(encodedResponse, Any.newBuilder())
                .build();
        final SpecEditedEvent actualResponse = anyResponseMessage.unpack(SpecEditedEvent.class);
        assertEquals(editSpecCommand, actualResponse.getCommand());
    }

    @Test
    public void testStopVm() throws InvalidProtocolBufferException {
        // arrange:
        final StopVmCommand stopVmCommand = StopVmCommand.newBuilder()
                .setId(nextId())
                .setVmId(nextId())
                .build();
        final Any anyMessage = Any.pack(stopVmCommand);
        final VmCommandEnvelope envelope = VmCommandEnvelope.newBuilder()
                .setInnerMessage(anyMessage)
                .setVmId(stopVmCommand.getId())
                .build();
        final String encodedCommand = encodeMessageBase64(envelope);

        // act:
        // 5.
        _sqsSender.sendMessage(REQUEST_QUEUE_URL, encodedCommand);
        final String encodedResponse = _sqsMessageReceiver.receiveSingleMessage(RESPONSE_QUEUE_URL);

        // assert:
        final Any anyResponseMessage = decodeMessageBase64(encodedResponse, Any.newBuilder())
                .build();
        final VmStoppedEvent actualResponse = anyResponseMessage.unpack(VmStoppedEvent.class);
        assertEquals(stopVmCommand, actualResponse.getCommand());
    }
}
