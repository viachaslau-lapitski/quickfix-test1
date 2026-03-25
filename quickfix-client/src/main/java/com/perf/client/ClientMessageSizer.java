package com.perf.client;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.util.Random;

final class ClientMessageSizer {
    private ClientMessageSizer() {}

    static NewOrderSingle buildTemplate(int targetBodyLength, Random random) {
        NewOrderSingle template = new NewOrderSingle(
                new ClOrdID("ORDER"),
                new HandlInst('1'),
                new Symbol("AAPL"),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.MARKET)
        );
        template.set(new OrderQty(100));
        applyMessageLength(template, targetBodyLength, random);
        return template;
    }

    static void applyMessageLength(NewOrderSingle message, int targetBodyLength, Random random) {
        if (targetBodyLength <= 0) {
            throw new IllegalArgumentException("len must be > 0");
        }

        message.setString(Text.FIELD, "");
        int baseLength = extractBodyLength(message);
        int needed = targetBodyLength - baseLength;
        if (needed < 0) {
            throw new IllegalArgumentException(
                "len is too small for required fields. min=" + baseLength);
        }

        message.setString(Text.FIELD, randomAscii(needed, random));
        int finalLength = extractBodyLength(message);
        if (finalLength != targetBodyLength) {
            int diff = targetBodyLength - finalLength;
            try {
                String text = message.getString(Text.FIELD);
                if (diff > 0) {
                    text = text + randomAscii(diff, random);
                } else if (diff < 0 && text.length() + diff >= 0) {
                    text = text.substring(0, text.length() + diff);
                }
                message.setString(Text.FIELD, text);
                finalLength = extractBodyLength(message);
            } catch (FieldNotFound e) {
                throw new IllegalStateException("Text field missing after sizing", e);
            }
        }
        if (finalLength != targetBodyLength) {
            throw new IllegalStateException(
                "Unable to match len. expected=" + targetBodyLength + " actual=" + finalLength);
        }
    }

    static int extractBodyLength(Message message) {
        String fix = message.toString();
        int start = fix.indexOf("9=");
        if (start < 0) {
            throw new IllegalStateException("BodyLength tag not found");
        }
        int valueStart = start + 2;
        int valueEnd = fix.indexOf('\u0001', valueStart);
        if (valueEnd < 0) {
            throw new IllegalStateException("BodyLength tag not terminated");
        }
        return Integer.parseInt(fix.substring(valueStart, valueEnd));
    }

    static String randomAscii(int length, Random random) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
