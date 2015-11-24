/*
 * Copyright 2015 Sam Sun <me@samczsun.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.samczsun.skype4j.internal.chat;

import com.eclipsesource.json.JsonObject;
import com.samczsun.skype4j.Skype;
import com.samczsun.skype4j.chat.Chat;
import com.samczsun.skype4j.chat.messages.ChatMessage;
import com.samczsun.skype4j.exceptions.ChatNotFoundException;
import com.samczsun.skype4j.exceptions.ConnectionException;
import com.samczsun.skype4j.exceptions.NotLoadedException;
import com.samczsun.skype4j.formatting.Message;
import com.samczsun.skype4j.formatting.Text;
import com.samczsun.skype4j.internal.*;
import com.samczsun.skype4j.internal.chat.messages.ChatMessageImpl;
import com.samczsun.skype4j.user.User;
import org.jsoup.helper.Validate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ChatImpl implements Chat {
    protected final AtomicBoolean isLoading = new AtomicBoolean(false);
    protected final AtomicBoolean hasLoaded = new AtomicBoolean(false);

    protected final Map<String, User> users = new ConcurrentHashMap<>();
    protected final List<ChatMessage> messages = new CopyOnWriteArrayList<>();

    private final SkypeImpl client;
    private final String identity;

    ChatImpl(SkypeImpl client, String identity) throws ConnectionException, ChatNotFoundException {
        this.client = client;
        this.identity = identity;
        load();
    }

    @Override
    public ChatMessage sendMessage(Message message) throws ConnectionException {
        checkLoaded();
        try {
            long ms = System.currentTimeMillis();

            JsonObject obj = new JsonObject();
            obj.add("content", message.write());
            obj.add("messagetype", "RichText");
            obj.add("contenttype", "text");
            obj.add("clientmessageid", String.valueOf(ms));

            ConnectionBuilder builder = new ConnectionBuilder();
            builder.setUrl(client.withCloud(Endpoints.SEND_MESSAGE_URL, getIdentity()));
            builder.setMethod("POST", true);
            builder.addHeader("RegistrationToken", client.getRegistrationToken());
            builder.addHeader("Content-Type", "application/json");
            builder.setData(obj.toString());
            HttpURLConnection con = builder.build();

            if (con.getResponseCode() != 201) {
                throw client.generateException("While sending message", con);
            }

            return ChatMessageImpl.createMessage(this, getUser(client.getUsername()), null, String.valueOf(ms), ms, message, getClient());
        } catch (IOException e) {
            throw this.client.generateException("While sending message", e);
        }
    }

    @Override
    public ChatMessage sendMessage(String plainMessage) throws ConnectionException {
        return sendMessage(Message.create().with(Text.plain(plainMessage)));
    }

    @Override
    public Collection<User> getAllUsers() {
        checkLoaded();
        return Collections.unmodifiableCollection(users.values());
    }

    @Override
    public User getUser(String username) {
        checkLoaded();
        return this.users.get(username.toLowerCase());
    }

    @Override
    public User getSelf() {
        return getUser(getClient().getUsername());
    }

    @Override
    public List<ChatMessage> getAllMessages() {
        checkLoaded();
        return Collections.unmodifiableList(messages);
    }

    @Override
    public String getIdentity() {
        return this.identity;
    }

    @Override
    public SkypeImpl getClient() {
        return this.client;
    }

    // Begin internal access methods
    public static Chat createChat(Skype client, String identity) throws ConnectionException, ChatNotFoundException {
        Validate.notNull(client, "Client must not be null");
        Validate.isTrue(client instanceof SkypeImpl, String.format("Now is not the time to use that, %s", client.getUsername()));
        Validate.notEmpty(identity, "Identity must not be null/empty");
        if (identity.startsWith("19:")) {
            if (identity.endsWith("@thread.skype")) {
                return new ChatGroup((SkypeImpl) client, identity);
            } else {
                throw new IllegalArgumentException(String.format("Cannot load P2P chat with identity %s", identity));
            }
        } else if (identity.startsWith("8:")) {
            return new ChatIndividual((SkypeImpl) client, identity);
        } else {
            throw new IllegalArgumentException(String.format("Unknown chat type with identity %s", identity));
        }
    }


    public void onMessage(ChatMessage message) {
        this.messages.add(message);
        ((UserImpl) message.getSender()).onMessage(message);
    }

    public boolean isLoaded() {
        return !isLoading.get() && hasLoaded.get();
    }

    public abstract void addUser(String username) throws ConnectionException;

    public abstract void removeUser(String username);

    protected abstract void load() throws ConnectionException, ChatNotFoundException;

    protected void checkLoaded() {
        if (!isLoaded()) {
            throw new NotLoadedException();
        }
    }
}
