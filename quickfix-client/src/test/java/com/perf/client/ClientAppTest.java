package com.perf.client;

import org.junit.jupiter.api.Test;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAppTest {

    @Test
    void buildTemplateMatchesTargetBodyLength() {
        int target = 120;
        NewOrderSingle message = ClientMessageSizer.buildTemplate(target, new Random(1));
        int actual = ClientMessageSizer.extractBodyLength(message);
        assertEquals(target, actual);
    }

    @Test
    void applyMessageLengthAdjustsBodyLength() {
        NewOrderSingle message = createBaseMessage();
        int base = ClientMessageSizer.extractBodyLength(message);
        int target = base + 15;
        ClientMessageSizer.applyMessageLength(message, target, new Random(2));
        int actual = ClientMessageSizer.extractBodyLength(message);
        assertEquals(target, actual);
    }

    @Test
    void applyMessageLengthRejectsTooSmallLen() {
        NewOrderSingle message = createBaseMessage();
        int base = ClientMessageSizer.extractBodyLength(message);
        assertThrows(IllegalArgumentException.class,
            () -> ClientMessageSizer.applyMessageLength(message, base - 1, new Random(3)));
    }

    private static NewOrderSingle createBaseMessage() {
        NewOrderSingle message = new NewOrderSingle(
                new ClOrdID("ORDER"),
                new HandlInst('1'),
                new Symbol("AAPL"),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.MARKET)
        );
        message.set(new OrderQty(100));
        return message;
    }
}
