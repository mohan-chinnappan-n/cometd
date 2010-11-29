/**
 *
 */
package org.cometd.oort;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.servlet.ServletContext;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.Listener;
import org.cometd.java.annotation.Service;
import org.cometd.java.annotation.Session;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.filter.DataFilter;
import org.cometd.server.filter.DataFilterMessageListener;
import org.cometd.server.filter.JSONDataFilter;
import org.cometd.server.filter.NoMarkupFilter;

@Service("chat")
public class OortChatService
{
    private final ConcurrentMap<String, Map<String,Boolean>> _members = new ConcurrentHashMap<String, Map<String,Boolean>>();
    @Inject
    private BayeuxServer _bayeux;
    @Session
    private ServerSession _session;
    private Oort _oort;
    private Seti _seti;

    OortChatService(ServletContext context)
    {
        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort==null)
            throw new RuntimeException("!"+Oort.OORT_ATTRIBUTE);
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti==null)
            throw new RuntimeException("!"+Seti.SETI_ATTRIBUTE);

        _oort.observeChannel("/chat/**");
    }

    @PostConstruct
    public void init()
    {
        final DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux,new NoMarkupFilter(),new BadWordFilter());

        if (!_bayeux.createIfAbsent("/chat/**",new ServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addListener(noMarkup);
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        }))
            throw new IllegalStateException();

        if (!_bayeux.createIfAbsent("/service/privatechat",new ServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
                channel.addListener(noMarkup);
                channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
            }
        }))
            throw new IllegalStateException();

        if (!_bayeux.createIfAbsent("/service/members",new ServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
                channel.setPersistent(true);
            }
        }))
            throw new IllegalStateException();
    }

    @Listener("/service/members")
    public void handleMembership(final ServerSession client, ServerMessage message)
    {
        Map<String, Object> data = message.getDataAsMap();
        String room = (String)data.get("room");
        Map<String,Boolean> roomMembers = _members.get(room);
        if (roomMembers == null)
        {
            Map<String,Boolean> newRoomMembers = new ConcurrentHashMap<String, Boolean>();
            roomMembers = _members.putIfAbsent(room, newRoomMembers);
            if (roomMembers == null) roomMembers = newRoomMembers;
        }
        final Map<String,Boolean> members = roomMembers;
        final String userName = (String)data.get("user");
        members.put(userName,Boolean.TRUE);
        client.addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                if (!_oort.isOort(client))
                    _seti.disassociate(userName);
                members.remove(userName);
                broadcastMembers(members.keySet());
            }
        });

        if (!_oort.isOort(client))
            _seti.associate(userName,client);

        broadcastMembers(members.keySet());
    }

    @Listener("/chat/members")
    public void handleMembershipBroadcast(final ServerSession client, ServerMessage message)
    {
        Object[] members = (Object[])message.getData();

        Map<String,Boolean> roomMembers = _members.get("/chat/demo");
        if (roomMembers == null)
        {
            Map<String,Boolean> newRoomMembers = new ConcurrentHashMap<String, Boolean>();
            roomMembers = _members.putIfAbsent("/chat/demo", newRoomMembers);
            if (roomMembers == null) roomMembers = newRoomMembers;
        }

        boolean added=false;
        for (Object o : members)
            added|=roomMembers.put(o.toString(),Boolean.TRUE)==null;

        if (added)
            broadcastMembers(roomMembers.keySet());
    }

    private void broadcastMembers(Set<String> members)
    {
        // Broadcast the new members list
        ClientSessionChannel channel = _session.getLocalSession().getChannel("/chat/members");
        channel.publish(members);
    }

    @Listener("/service/privatechat")
    public void privateChat(ServerSession client, ServerMessage message)
    {
        Map<String,Object> data = message.getDataAsMap();
        String toUid=(String)data.get("peer");
        String toChannel=(String)data.get("room");
        data.put("scope","private");
        data.put("user",data.get("user")+"->"+toUid);
        client.deliver(client,toChannel,data,message.getId());
        _seti.sendMessage(toUid,toChannel,data);
    }

    class BadWordFilter extends JSONDataFilter
    {
        @Override
        protected Object filterString(String string)
        {
            if (string.indexOf("dang")>=0)
                throw new DataFilter.Abort();
            return string;
        }
    }
}

