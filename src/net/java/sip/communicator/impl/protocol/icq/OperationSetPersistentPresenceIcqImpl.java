/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.icq;

import java.beans.*;
import java.util.*;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.service.protocol.icqconstants.*;
import net.java.sip.communicator.service.protocol.AuthorizationResponse.*;
import net.java.sip.communicator.util.*;
import net.kano.joscar.*;
import net.kano.joscar.flapcmd.*;
import net.kano.joscar.snac.*;
import net.kano.joscar.snaccmd.*;
import net.kano.joscar.snaccmd.conn.*;
import net.kano.joscar.snaccmd.error.*;
import net.kano.joscar.snaccmd.loc.*;
import net.kano.joustsim.*;
import net.kano.joustsim.oscar.*;
import net.kano.joustsim.oscar.oscar.service.bos.*;
import net.kano.joustsim.oscar.oscar.service.buddy.*;
import net.kano.joustsim.oscar.oscar.service.ssi.*;
import net.kano.joustsim.oscar.oscar.service.icon.*;


/**
 * The ICQ implementation of a Persistent Presence Operation set. This class
 * manages our own presence status as well as subscriptions for the presence
 * status of our buddies. It also offers methods for retrieving and modifying
 * the buddy contact list and adding listeners for changes in its layout.
 *
 * @author Emil Ivov
 */
public class OperationSetPersistentPresenceIcqImpl
    implements OperationSetPersistentPresence
{
    private static final Logger logger =
        Logger.getLogger(OperationSetPersistentPresenceIcqImpl.class);

    /**
     * A callback to the ICQ provider that created us.
     */
    private ProtocolProviderServiceIcqImpl icqProvider = null;

    /**
     * The list of presence status listeners interested in receiving presence
     * notifications of changes in status of contacts in our contact list.
     */
    private Vector contactPresenceStatusListeners = new Vector();

    /**
     * The list of subscription listeners interested in receiving  notifications
     * whenever .
     */
    private Vector subscriptionListeners = new Vector();

    /**
     * The list of listeners interested in receiving changes in our local
     * presencestatus.
     */
    private Vector providerPresenceStatusListeners = new Vector();

    /**
     * Listeners notified upon changes occurring with server stored contact
     * groups.
     */
    private Vector serverStoredGroupChangeListeners = new Vector();

    /**
     * This one should actually be in joscar. But since it isn't we might as
     * well define it here.
     */
    private static final long ICQ_ONLINE_MASK = 0x01000000L;

    /**
     * The IcqContact representing the local protocol provider.
     */
    private ContactIcqImpl localContact = null;

    /**
     * The listener that would react upon changes of the registration state of
     * our provider
     */
    private RegistrationStateListener registrationStateListener
        = new RegistrationStateListener();

    /**
     * The listener that would receive joust sim status updates for budddies in
     * our contact list
     */
    private JoustSimBuddyServiceListener joustSimBuddySerListener
        = new JoustSimBuddyServiceListener();

    /**
     * emcho: I think Bos stands for Buddy Online-status Service ... or at least
     * it seems like a plausible translation. This listener follows changes
     * in our own presence status and translates them in the corresponding
     * protocol provider events.
     */
    private JoustSimBosListener joustSimBosListener = new JoustSimBosListener();

    /**
     * Contains our current status message. Note that this field would only
     * be changed once the server has confirmed the new status message and
     * not immediately upon setting a new one..
     */
    private String currentStatusMessage = "";

    /**
     * The presence status that we were last notified of etnering.
     */
    private long currentIcqStatus = -1;

    private AuthorizationHandler authorizationHandler = null;
    private AuthListener authListener = new AuthListener();
    
    /**
     * The timer scheduling task that will query awaiting authorization
     * contacts for their status
     */
    private Timer presenceQueryTimer = null;
    
    /**
     *  Interval between queries for awaiting authorization
     *  contact statuses
     */ 
    private long PRESENCE_QUERY_INTERVAL = 120000l;

    /**
     *  Used to request authorization when a user comes online 
     *  and haven't granted one
     */
    private OperationSetExtendedAuthorizationsIcqImpl opSetExtendedAuthorizations = null;
    
    /**
     *  Buddies seen availabel
     */
    private Vector buddiesSeenAvailable = new Vector();
    
    /**
     * The array list we use when returning from the getSupportedStatusSet()
     * method.
     */
    private ArrayList supportedPresenceStatusSet = new ArrayList();

    /**
     * A map containing bindings between SIP Communicator's icq presence status
     * instances and ICQ status codes
     */
    private static Map scToIcqStatusMappings = new Hashtable();
    static{

        scToIcqStatusMappings.put(IcqStatusEnum.AWAY,
                                  new Long(FullUserInfo.ICQSTATUS_AWAY));
        scToIcqStatusMappings.put(IcqStatusEnum.DO_NOT_DISTURB,
                                  new Long(FullUserInfo.ICQSTATUS_DND ));
        scToIcqStatusMappings.put(IcqStatusEnum.FREE_FOR_CHAT,
                                  new Long(FullUserInfo.ICQSTATUS_FFC ));
        scToIcqStatusMappings.put(IcqStatusEnum.INVISIBLE,
                                  new Long(FullUserInfo.ICQSTATUS_INVISIBLE));
        scToIcqStatusMappings.put(IcqStatusEnum.NOT_AVAILABLE,
                                  new Long(FullUserInfo.ICQSTATUS_NA));
        scToIcqStatusMappings.put(IcqStatusEnum.OCCUPIED,
                                  new Long(FullUserInfo.ICQSTATUS_OCCUPIED));
        scToIcqStatusMappings.put(IcqStatusEnum.ONLINE,
                                  new Long(ICQ_ONLINE_MASK));

    }
    
    /**
     * The server stored contact list that will be encapsulating joustsim's
     * buddy list.
     */
    private ServerStoredContactListIcqImpl ssContactList = null;

    /**
     * Creates a new Presence OperationSet over the specified icq provider.
     * @param icqProvider IcqProtocolProviderServiceImpl
     * @param uin the UIN of our account.
     */
    protected OperationSetPersistentPresenceIcqImpl(
                    ProtocolProviderServiceIcqImpl icqProvider,
                    String uin)
    {
        this.icqProvider = icqProvider;

        ssContactList = new ServerStoredContactListIcqImpl( this , icqProvider);

        //add a listener that'll follow the provider's state.
        icqProvider.addRegistrationStateChangeListener(
            registrationStateListener);
    }

    /**
     * Registers a listener that would receive a presence status change event
     * every time a contact, whose status we're subscribed for, changes her
     * status.
     * Note that, for reasons of simplicity and ease of implementation, there
     * is only a means of registering such "global" listeners that would receive
     * updates for status changes for any contact and it is not currently
     * possible to register such contacts for a single contact or a subset of
     * contacts.
     *
     * @param listener the listener that would received presence status
     * updates for contacts.
     */
    public void addContactPresenceStatusListener(
        ContactPresenceStatusListener listener)
    {
        synchronized(contactPresenceStatusListeners)
        {
            if(!contactPresenceStatusListeners.contains(listener))
                this.contactPresenceStatusListeners.add(listener);
        }
    }

    /**
     * Removes the specified listener so that it won't receive any further
     * updates on contact presence status changes
     * @param listener the listener to remove.
     */
    public void removeContactPresenceStatusListener(
        ContactPresenceStatusListener listener)
    {
        synchronized(contactPresenceStatusListeners){
            contactPresenceStatusListeners.remove(listener);
        }
    }

    /**
     * Registers a listener that would get notifications any time a new
     * subscription was succesfully added, has failed or was removed.
     * @param listener the SubscriptionListener to register
     */
    public void addSubsciptionListener(SubscriptionListener listener)
    {
        synchronized(subscriptionListeners)
        {
            if(!subscriptionListeners.contains(listener))
                subscriptionListeners.add(listener);
        }
    }

    /**
     * Removes the specified subscription listener.
     * @param listener the listener to remove.
     */
    public void removeSubscriptionListener(SubscriptionListener listener)
    {
        synchronized(subscriptionListeners){
            subscriptionListeners.remove(listener);
        }
    }

    /**
     * Get the PresenceStatus for a particular contact. This method is not meant
     * to be used by the user interface (which would simply register as a
     * presence listener and always follow contact status) but rather by other
     * plugins that may for some reason need to know the status of a particular
     * contact.
     * <p>
     * @param contactIdentifier the dientifier of the contact whose status we're
     * interested in.
     * @return PresenceStatus the <tt>PresenceStatus</tt> of the specified
     * <tt>contact</tt>
     * @throws java.lang.IllegalStateException if the provider is not signed
     * on ICQ
     * @throws java.lang.IllegalArgumentException if <tt>contact</tt> is not
     * a valid <tt>IcqContact</tt>
     */
    public PresenceStatus queryContactStatus(String contactIdentifier)
        throws IllegalStateException, IllegalArgumentException
    {
        assertConnected();

        //these are commented since we now use identifiers.
        //        if (!(contact instanceof ContactIcqImpl))
        //            throw new IllegalArgumentException(
        //                "Cannont get status for a non-ICQ contact! ("
        //                + contact + ")");
        //
        //        ContactIcqImpl contactImpl = (ContactIcqImpl)contact;

        StatusResponseRetriever responseRetriever =
            new StatusResponseRetriever();

        GetInfoCmd getInfoCmd =
            new GetInfoCmd(GetInfoCmd.CMD_USER_INFO, contactIdentifier);

        icqProvider.getAimConnection().getInfoService().getOscarConnection()
            .sendSnacRequest(getInfoCmd, responseRetriever);

        synchronized(responseRetriever)
        {
            try{
                responseRetriever.wait(10000);
            }
            catch (InterruptedException ex){
                //we don't care
            }
        }

        return icqStatusLongToPresenceStatus(responseRetriever.status);
    }

    /**
     * Converts the specified icqstatus to one of the status fields of the
     * IcqStatusEnum class.
     *
     * @param icqStatus the icqStatus as retured in FullUserInfo by the joscar
     *        stack
     * @return a PresenceStatus instance representation of the "long" icqStatus
     * parameter. The returned result is one of the IcqStatusEnum fields.
     */
    private IcqStatusEnum icqStatusLongToPresenceStatus(long icqStatus)
    {
        // Fixed order of status checking
        // The order does matter, as the icqStatus consists of more than one
        // status for example DND = OCCUPIED | DND | AWAY
        if(icqStatus == -1)
        {
            return IcqStatusEnum.OFFLINE;
        }
        else if ( (icqStatus & FullUserInfo.ICQSTATUS_INVISIBLE ) != 0)
        {
            return IcqStatusEnum.INVISIBLE;
        }
        else if ( (icqStatus & FullUserInfo.ICQSTATUS_DND ) != 0)
        {
            return IcqStatusEnum.DO_NOT_DISTURB;
        }
        else if ( (icqStatus & FullUserInfo.ICQSTATUS_OCCUPIED ) != 0)
        {
            return IcqStatusEnum.OCCUPIED;
        }
        else if ( (icqStatus & FullUserInfo.ICQSTATUS_NA ) != 0)
        {
            return IcqStatusEnum.NOT_AVAILABLE;
        }
        else if ( (icqStatus & FullUserInfo.ICQSTATUS_AWAY ) != 0)
        {
            return IcqStatusEnum.AWAY;
        }
        else if ( (icqStatus & FullUserInfo.ICQSTATUS_FFC ) != 0)
        {
            return IcqStatusEnum.FREE_FOR_CHAT;
        }

        // FIXED:  Issue 70
        // Incomplete status information in ICQ

        // if none of the statuses is satisfied
        // then the default is Online
        // there is no such status send from the server as Offline
        // when received error from server, after a query
        // the status is -1 so Offline
//        else if ((icqStatus & ICQ_ONLINE_MASK) == 0 )
//        {
//            return IcqStatusEnum.OFFLINE;
//        }

        return IcqStatusEnum.ONLINE;
    }

    /**
     * Converts the specified IcqStatusEnum member to the corresponding ICQ
     * flag.
     *
     * @param status the icqStatus as retured in FullUserInfo by the joscar
     *        stack
     * @return a PresenceStatus instance representation of the "long" icqStatus
     * parameter. The returned result is one of the IcqStatusEnum fields.
     */
    private long presenceStatusToIcqStatusLong(IcqStatusEnum status)
    {
        return ((Long)scToIcqStatusMappings.get(status)).longValue();
    }

    /**
     * Adds a subscription for the presence status of the contact corresponding
     * to the specified contactIdentifier. Apart from an exception in the case
     * of an immediate failure, the method won't return any indication of
     * success or failure. That would happen later on through a
     * SubscriptionEvent generated by one of the methods of the
     * SubscriptionListener.
     * <p>
     * This subscription is not going to be persistent (as opposed to
     * subscriptions added from the OperationSetPersistentPresence.subscribe()
     * method)
     * <p>
     * @param contactIdentifier the identifier of the contact whose status
     * updates we are subscribing for.
     * <p>
     * @throws OperationFailedException with code NETWORK_FAILURE if subscribing
     * fails due to errors experienced during network communication
     * @throws IllegalArgumentException if <tt>contact</tt> is not a contact
     * known to the underlying protocol provider
     * @throws IllegalStateException if the underlying protocol provider is not
     * registered/signed on a public service.
     */
    public void subscribe(String contactIdentifier)
        throws IllegalArgumentException,
               IllegalStateException,
               OperationFailedException
    {
        assertConnected();

        ssContactList.addContact(contactIdentifier);
    }

    /**
     * Creates a non persistent contact for the specified address. This would
     * also create (if necessary) a group for volatile contacts that would not
     * be added to the server stored contact list. The volatile contact would
     * remain in the list until it is really added to the contact list or
     * until the application is terminated.
     * @param uin the UIN/Screenname of the contact to create.
     * @return the newly created volatile <tt>ContactIcqImpl</tt>
     */
    public ContactIcqImpl createVolatileContact(String uin)
    {
        return ssContactList.createVolatileContact(new Screenname(uin));
    }

    /**
     * Creates and returns a unresolved contact from the specified
     * <tt>address</tt> and <tt>persistentData</tt>. The method will not try
     * to establish a network connection and resolve the newly created Contact
     * against the server. The protocol provider may will later try and resolve
     * the contact. When this happens the corresponding event would notify
     * interested subscription listeners.
     *
     * @param address an identifier of the contact that we'll be creating.
     * @param persistentData a String returned Contact's getPersistentData()
     * method during a previous run and that has been persistently stored
     * locally.
     * @param parentGroup the group that the unresolved contact should belong to.
     * @return the unresolved <tt>Contact</tt> created from the specified
     * <tt>address</tt> and <tt>persistentData</tt>
     *
     * @throws java.lang.IllegalArgumentException if <tt>parentGroup</tt> is not
     * an instance of ContactGroupIcqImpl
     */
    public Contact createUnresolvedContact(String address,
                                           String persistentData,
                                           ContactGroup parentGroup)
        throws IllegalArgumentException
    {
        if(! (parentGroup instanceof ContactGroupIcqImpl) )
            throw new IllegalArgumentException(
                "Argument is not an icq contact group (group="
                + parentGroup + ")");

        ContactIcqImpl contact =
            ssContactList.createUnresolvedContact(
            (ContactGroupIcqImpl)parentGroup, new Screenname(address));

        contact.setPersistentData(persistentData);

        return contact;
    }

    /**
     * Creates and returns a unresolved contact from the specified
     * <tt>address</tt> and <tt>persistentData</tt>. The method will not try
     * to establish a network connection and resolve the newly created Contact
     * against the server. The protocol provider may will later try and resolve
     * the contact. When this happens the corresponding event would notify
     * interested subscription listeners.
     *
     * @param address an identifier of the contact that we'll be creating.
     * @param persistentData a String returned Contact's getPersistentData()
     * method during a previous run and that has been persistently stored
     * locally.
     *
     * @return the unresolved <tt>Contact</tt> created from the specified
     * <tt>address</tt> and <tt>persistentData</tt>
     */
    public Contact createUnresolvedContact(String address,
                                           String persistentData)
    {
        return createUnresolvedContact(  address
                                       , persistentData
                                       , getServerStoredContactListRoot());
    }

    /**
     * Creates and returns a unresolved contact group from the specified
     * <tt>address</tt> and <tt>persistentData</tt>. The method will not try
     * to establish a network connection and resolve the newly created
     * <tt>ContactGroup</tt> against the server or the contact itself. The
     * protocol provider will later resolve the contact group. When this happens
     * the corresponding event would notify interested subscription listeners.
     *
     * @param groupUID an identifier, returned by ContactGroup's getGroupUID,
     * that the protocol provider may use in order to create the group.
     * @param persistentData a String returned ContactGroups's getPersistentData()
     * method during a previous run and that has been persistently stored
     * locally.
     * @param parentGroup the group under which the new group is to be created
     * or null if this is group directly underneath the root.
     * @return the unresolved <tt>ContactGroup</tt> created from the specified
     * <tt>uid</tt> and <tt>persistentData</tt>
     */
    public ContactGroup createUnresolvedContactGroup(String groupUID,
        String persistentData, ContactGroup parentGroup)
    {
        //silently ignore the parent group. ICQ does not support subgroups so
        //parentGroup is supposed to be root. if it is not however we're not
        //going to complain because we're cool :).
        return ssContactList.createUnresolvedContactGroup(groupUID);
    }

    /**
     * Persistently adds a subscription for the presence status of the  contact
     * corresponding to the specified contactIdentifier and indicates that it
     * should be added to the specified group of the server stored contact list.
     * Note that apart from an exception in the case of an immediate failure,
     * the method won't return any indication of success or failure. That would
     * happen later on through a SubscriptionEvent generated by one of the
     * methods of the SubscriptionListener.
     * <p>
     * @param contactIdentifier the contact whose status updates we are subscribing
     *   for.
     * @param parent the parent group of the server stored contact list where
     * the contact should be added.
     * <p>
     * <p>
     * @throws OperationFailedException with code NETWORK_FAILURE if subscribing
     * fails due to errors experienced during network communication
     * @throws IllegalArgumentException if <tt>contact</tt> or
     * <tt>parent</tt> are not a contact known to the underlying protocol
     * provider.
     * @throws IllegalStateException if the underlying protocol provider is not
     * registered/signed on a public service.
     */
    public void subscribe(ContactGroup parent, String contactIdentifier)
        throws IllegalArgumentException,
               IllegalStateException,
               OperationFailedException
    {
        assertConnected();

        if(! (parent instanceof ContactGroupIcqImpl) )
            throw new IllegalArgumentException(
                "Argument is not an icq contact group (group=" + parent + ")");

        ssContactList.addContact((ContactGroupIcqImpl)parent, contactIdentifier);
    }

    /**
     * Removes a subscription for the presence status of the specified contact.
     * @param contact the contact whose status updates we are unsubscribing from.
     *
     * @throws OperationFailedException with code NETWORK_FAILURE if unsubscribing
     * fails due to errors experienced during network communication
     * @throws IllegalArgumentException if <tt>contact</tt> is not a contact
     * known to this protocol provider or is not an ICQ contact
     * @throws IllegalStateException if the underlying protocol provider is not
     * registered/signed on a public service.
     */
    public void unsubscribe(Contact contact) throws IllegalArgumentException,
        IllegalStateException, OperationFailedException
    {
        assertConnected();

        if(! (contact instanceof ContactIcqImpl) )
            throw new IllegalArgumentException(
                "Argument is not an icq contact (contact=" + contact + ")");

        ContactIcqImpl contactIcqImpl = (ContactIcqImpl)contact;

        ContactGroupIcqImpl contactGroup
            = ssContactList.findContactGroup(contactIcqImpl);

        if (contactGroup == null)
            throw new IllegalArgumentException(
              "The specified contact was not found on the local "
              +"contact/subscription list: " + contact);

        if(!contactIcqImpl.isPersistent())
        {
            contactGroup.removeContact(contactIcqImpl);
            fireSubscriptionEvent(SubscriptionEvent.SUBSCRIPTION_REMOVED,
                                  contactIcqImpl,
                                  contactGroup);

            return;
        }

        logger.trace("Going to remove contact from ss-list : " + contact);
        
        if( !contactGroup.isPersistent()
            && contactIcqImpl.getJoustSimBuddy().isAwaitingAuthorization())
        {
            // this is contact in AwaitingAuthorization group
            // we must find the original parent and remove it from there
            ContactGroupIcqImpl origParent = 
                ssContactList.findGroup(contactIcqImpl.getJoustSimBuddy());
            
            if(origParent != null)
            {
                origParent.getJoustSimSourceGroup().
                    deleteBuddy(contactIcqImpl.getJoustSimBuddy());
            }
        }
        else
        {
            MutableGroup joustSimContactGroup = contactGroup.getJoustSimSourceGroup();

            joustSimContactGroup.deleteBuddy(contactIcqImpl.getJoustSimBuddy());
        }
    }

    /**
     * Returns a reference to the contact with the specified ID in case we have
     * a subscription for it and null otherwise/
     * @param contactID a String identifier of the contact which we're seeking a
     * reference of.
     * @return a reference to the Contact with the specified
     * <tt>contactID</tt> or null if we don't have a subscription for the
     * that identifier.
     */
    public Contact findContactByID(String contactID)
    {
        return ssContactList.findContactByScreenName(contactID);
    }

    /**
     * Requests the provider to enter into a status corresponding to the
     * specified paramters. Note that calling this method does not necessarily
     * imply that the requested status would be entered. This method would
     * return right after being called and the caller should add itself as
     * a listener to this class in order to get notified when the state has
     * actually changed.
     *
     * @param status the PresenceStatus as returned by getRequestableStatusSet
     * @param statusMessage the message that should be set as the reason to
     * enter that status
     * @throws IllegalArgumentException if the status requested is not a valid
     * PresenceStatus supported by this provider.
     * @throws java.lang.IllegalStateException if the provider is not currently
     * registered.
     * @throws OperationFailedException with code NETWORK_FAILURE if publishing
     * the status fails due to a network error.
     */
    public void publishPresenceStatus(PresenceStatus status,
                                      String statusMessage) throws
        IllegalArgumentException, IllegalStateException,
        OperationFailedException
    {
        assertConnected();

        if (!(status instanceof IcqStatusEnum))
            throw new IllegalArgumentException(
                            status + " is not a valid ICQ status");
        
        long icqStatus = presenceStatusToIcqStatusLong((IcqStatusEnum)status);

        logger.debug("Will set status: " + status + " long=" + icqStatus);

        MainBosService bosService
            = icqProvider.getAimConnection().getBosService();
        
        if(!icqProvider.USING_ICQ)
        {
            if(status.equals(IcqStatusEnum.AWAY))
            {
                if(getPresenceStatus().equals(IcqStatusEnum.INVISIBLE))
                    bosService.setVisibleStatus(true);
                
                bosService.getOscarConnection().sendSnac(new SetInfoCmd(
                    new InfoData(null, "I'm away!", null, null)));
            }
            else if(status.equals(IcqStatusEnum.INVISIBLE))
            {
                if(getPresenceStatus().equals(IcqStatusEnum.AWAY))
                    bosService.getOscarConnection().sendSnac(new SetInfoCmd(
                        new InfoData(null, InfoData.NOT_AWAY, null, null)));
                
                bosService.setVisibleStatus(false);
            }
            else if(status.equals(IcqStatusEnum.ONLINE))
            {
                if(getPresenceStatus().equals(IcqStatusEnum.INVISIBLE))
                    bosService.setVisibleStatus(true);
                else if(getPresenceStatus().equals(IcqStatusEnum.AWAY))
                {
                    bosService.getOscarConnection().sendSnac(new SetInfoCmd(
                        new InfoData(null, InfoData.NOT_AWAY, null, null)));
                }
            }
        }
        else
        {
            bosService.getOscarConnection().sendSnac(new SetExtraInfoCmd(icqStatus));
            bosService.setStatusMessage(statusMessage);
        }

        //so that everyone sees the change.
        queryContactStatus(
            icqProvider.getAimConnection().getScreenname().getFormatted());
    }

    /**
     * Returns the status message that was confirmed by the serfver
     * @return the last status message that we have requested and the aim server
     * has confirmed.
     */
    public String getCurrentStatusMessage()
    {
        return this.currentStatusMessage;
    }

    /**
     * Returns the protocol specific contact instance representing the local
     * user.
     *
     * @return the Contact (address, phone number, or uin) that the Provider
     *   implementation is communicating on behalf of.
     */
    public Contact getLocalContact()
    {
        return localContact;
    }

    /**
     * Creates a group with the specified name and parent in the server stored
     * contact list.
     * @param groupName the name of the new group to create.
     * @param parent the group where the new group should be created
     *
     * @throws OperationFailedException with code NETWORK_FAILURE if unsubscribing
     * fails due to errors experienced during network communication
     * @throws IllegalArgumentException if <tt>contact</tt> is not a contact
     * known to the underlying protocol provider
     * @throws IllegalStateException if the underlying protocol provider is not
     * registered/signed on a public service.
     */
    public void createServerStoredContactGroup(ContactGroup parent,
        String groupName)
    {
        assertConnected();

        if (!parent.canContainSubgroups())
            throw new IllegalArgumentException(
                "The specified contact group cannot contain child groups. Group:"
                + parent );

        ssContactList.createGroup(groupName);
    }

    /**
     * Removes the specified group from the server stored contact list.
     * @param group the group to remove.
     *
     * @throws OperationFailedException with code NETWORK_FAILURE if deleting
     * the group fails because of a network error.
     * @throws IllegalArgumentException if <tt>parent</tt> is not a contact
     * known to the underlying protocol provider.
     * @throws IllegalStateException if the underlying protocol provider is not
     * registered/signed on a public service.
     */
    public void removeServerStoredContactGroup(ContactGroup group)
    {
        assertConnected();

        if( !(group instanceof ContactGroupIcqImpl) )
            throw new IllegalArgumentException(
                "The specified group is not an icq contact group: " + group);

        ssContactList.removeGroup((ContactGroupIcqImpl)group);
    }

    /**
     * Renames the specified group from the server stored contact list. This
     * method would return before the group has actually been renamed. A
     * <tt>ServerStoredGroupEvent</tt> would be dispatched once new name
     * has been acknowledged by the server.
     *
     * @param group the group to rename.
     * @param newName the new name of the group.
     *
     * @throws OperationFailedException with code NETWORK_FAILURE if deleting
     * the group fails because of a network error.
     * @throws IllegalArgumentException if <tt>parent</tt> is not a contact
     * known to the underlying protocol provider.
     * @throws IllegalStateException if the underlying protocol provider is not
     * registered/signed on a public service.
     */
    public void renameServerStoredContactGroup(
                    ContactGroup group, String newName)
    {
        assertConnected();

        if( !(group instanceof ContactGroupIcqImpl) )
            throw new IllegalArgumentException(
                "The specified group is not an icq contact group: " + group);

        ssContactList.renameGroup((ContactGroupIcqImpl)group, newName);
    }

    /**
     * Removes the specified contact from its current parent and places it
     * under <tt>newParent</tt>.
     * @param contactToMove the <tt>Contact</tt> to move
     * @param newParent the <tt>ContactGroup</tt> where <tt>Contact</tt>
     * would be placed.
     */
    public void moveContactToGroup(Contact contactToMove,
                                   ContactGroup newParent)
    {
        assertConnected();

        if( !(contactToMove instanceof ContactIcqImpl) )
            throw new IllegalArgumentException(
                "The specified contact is not an icq contact." + contactToMove);
        if( !(newParent instanceof ContactGroupIcqImpl) )
            throw new IllegalArgumentException(
                "The specified group is not an icq contact group."
                + newParent);
        
        ContactGroupIcqImpl theAwaitingAuthorizationGroup = 
            ssContactList.findContactGroup(
                ssContactList.awaitingAuthorizationGroupName);
        
        if(newParent.equals(theAwaitingAuthorizationGroup))
                throw new IllegalArgumentException(
                "Cannot move contacts to this group : " + 
                    theAwaitingAuthorizationGroup);
        
        if(((ContactIcqImpl)contactToMove).isPersistent() 
            && !contactToMove.getParentContactGroup().isPersistent())
        {
            if(contactToMove.getParentContactGroup().equals(
                theAwaitingAuthorizationGroup))
                throw new IllegalArgumentException(
                "Cannot move contacts from this group : " + 
                    theAwaitingAuthorizationGroup);
        }
        
        ssContactList.moveContact((ContactIcqImpl)contactToMove,
                                  (ContactGroupIcqImpl)newParent);
    }

    /**
     * Returns a snapshot ieves a server stored list of subscriptions/contacts that have been
     * made previously. Note that the contact list returned by this method may
     * be incomplete as it is only a snapshot of what has been retrieved through
     * the network up to the moment when the method is called.
     * @return a ConactGroup containing all previously made subscriptions stored
     * on the server.
     */
    ServerStoredContactListIcqImpl getServerStoredContactList()
    {
        return ssContactList;
    }

    /**
     * Returns a PresenceStatus instance representing the state this provider
     * is currently in.
     *
     * @return PresenceStatus
     */
    public PresenceStatus getPresenceStatus()
    {
        return icqStatusLongToPresenceStatus(currentIcqStatus);
    }

    /**
     * Returns the set of PresenceStatus objects that a user of this service
     * may request the provider to enter.
     *
     * @return Iterator a PresenceStatus array containing "enterable" status
     *   instances.
     */
    public Iterator getSupportedStatusSet()
    {
        if(supportedPresenceStatusSet.size() == 0)
        {
            supportedPresenceStatusSet.add(IcqStatusEnum.ONLINE);
            
            if(icqProvider.USING_ICQ)
            {
                supportedPresenceStatusSet.add(IcqStatusEnum.DO_NOT_DISTURB);
                supportedPresenceStatusSet.add(IcqStatusEnum.FREE_FOR_CHAT);
                supportedPresenceStatusSet.add(IcqStatusEnum.NOT_AVAILABLE);
                supportedPresenceStatusSet.add(IcqStatusEnum.OCCUPIED);                
            }
            
            supportedPresenceStatusSet.add(IcqStatusEnum.AWAY);
            supportedPresenceStatusSet.add(IcqStatusEnum.INVISIBLE);
            supportedPresenceStatusSet.add(IcqStatusEnum.OFFLINE);
        }
        
        return supportedPresenceStatusSet.iterator();
    }

    /**
     * Handler for incoming authorization requests. An authorization request
     * notifies the user that someone is trying to add her to their contact list
     * and requires her to approve or reject authorization for that action.
     * @param handler an instance of an AuthorizationHandler for authorization
     * requests coming from other users requesting permission add us to their
     * contact list.
     */
    public void setAuthorizationHandler(AuthorizationHandler handler)
    {
        /** @todo
         * method to be removed and AuthorizationHandler to be set
         * set upon creation of the
         * provider so that there could be only one.
         *
         **/
        this.authorizationHandler = handler;

        icqProvider.getAimConnection().getSsiService().
            addBuddyAuthorizationListener(authListener);
    }

    /**
     * The StatusResponseRetriever is used as a one time handler for responses
     * to requests sent through the sendSnacRequest method of one of joustsim's
     * Services. The StatusResponseRetriever would ignore everything apart from
     * the first response, which will be stored in the status field. In the
     * case of a timeout, the status would remain on -1. Both a response and
     * a timeout would make the StatusResponseRetriever call its notifyAll
     * method so that those that are waiting on it be notified.
     */
    private class StatusResponseRetriever extends SnacRequestAdapter
    {
            private boolean ran = false;
            private long status = -1;


            public void handleResponse(SnacResponseEvent e) {
                SnacCommand snac = e.getSnacCommand();

                synchronized(this) {
                    if (ran) return;
                    ran = true;
                }

                Object value = null;
                if (snac instanceof UserInfoCmd)
                {
                    UserInfoCmd uic = (UserInfoCmd) snac;

                    FullUserInfo userInfo = uic.getUserInfo();
                    if (userInfo != null)
                    {
                        this.status = userInfo.getIcqStatus();

                        //it is possible that the status was not included in
                        //the UserInfoCmd. Yet the fact that we got one
                        //guarantees that she is not offline. we'll therefore
                        //make sure it does not remain on -1.
                        if (this.status == -1)
                            status = ICQ_ONLINE_MASK;

                        synchronized(this){
                            this.notifyAll();
                        }
                    }
                }
                else if( snac instanceof SnacError)
                {
                    //this is most probably a CODE_USER_UNAVAILABLE, but
                    //whatever it is it means that to us the buddy in question
                    //is as good as offline so leave status at -1 and notify.

                    logger.debug("status is" + status);
                    synchronized(this){
                        this.notifyAll();
                    }
                }

            }

            public void handleTimeout(SnacRequestTimeoutEvent event) {
                synchronized(this) {
                    if (ran) return;
                    ran = true;
                    notifyAll();
                }
            }
    }

    /**
     * Utility method throwing an exception if the icq stack is not properly
     * initialized.
     * @throws java.lang.IllegalStateException if the underlying ICQ stack is
     * not registered and initialized.
     */
    private void assertConnected() throws IllegalStateException
    {
        if (icqProvider == null)
            throw new IllegalStateException(
                "The icq provider must be non-null and signed on the ICQ "
                +"service before being able to communicate.");
        if (!icqProvider.isRegistered())
            throw new IllegalStateException(
                "The icq provider must be signed on the ICQ service before "
                +"being able to communicate.");
    }

    /**
     * Adds a listener that would receive events upon changes of the provider
     * presence status.
     * @param listener the listener to register for changes in our PresenceStatus.
     */
    public void addProviderPresenceStatusListener(
        ProviderPresenceStatusListener listener)
    {
        synchronized(providerPresenceStatusListeners)
        {
            if(!providerPresenceStatusListeners.contains(listener))
                providerPresenceStatusListeners.add(listener);
        }
    }

    /**
     * Unregisters the specified listener so that it does not receive further
     * events upon changes in local presence status.
     * @param listener ProviderPresenceStatusListener
     */
    public void removeProviderPresenceStatusListener(
        ProviderPresenceStatusListener listener)
    {
        synchronized(providerPresenceStatusListeners)
        {
            providerPresenceStatusListeners.remove(listener);
        }
    }

    /**
     * Returns the root group of the server stored contact list. Most often this
     * would be a dummy group that user interface implementations may better not
     * show.
     *
     * @return the root ContactGroup for the ContactList stored by this service.
     */
    public ContactGroup getServerStoredContactListRoot()
    {
        return ssContactList.getRootGroup();
    }

    /**
     * Registers a listener that would receive events upong changes in server
     * stored groups.
     * @param listener a ServerStoredGroupChangeListener impl that would receive
     * events upong group changes.
     */
    public void addServerStoredGroupChangeListener(
        ServerStoredGroupListener listener)
    {
        ssContactList.addGroupListener(listener);
    }

    /**
     * Removes the specified group change listener so that it won't receive
     * any further events.
     * @param listener the ServerStoredGroupChangeListener to remove
     */
    public void removeServerStoredGroupChangeListener(
        ServerStoredGroupListener listener)
    {
        ssContactList.removeGroupListener(listener);
    }

    /**
     * Notify all provider presence listeners of the corresponding event change
     * @param oldStatusL the status our icq stack had so far
     * @param newStatusL the status we have from now on
     */
    void fireProviderPresenceStatusChangeEvent(
                        long oldStatusL, long newStatusL)
    {
        PresenceStatus oldStatus = icqStatusLongToPresenceStatus(oldStatusL);
        PresenceStatus newStatus = icqStatusLongToPresenceStatus(newStatusL);

        if(oldStatus.equals(newStatus)){
            logger.debug("Ignored prov stat. change evt. old==new = "
                         + oldStatus);
            return;
        }

        ProviderPresenceStatusChangeEvent evt =
            new ProviderPresenceStatusChangeEvent(
                icqProvider, oldStatus, newStatus);

        logger.debug("Dispatching Provider Status Change. Listeners="
                     + providerPresenceStatusListeners.size()
                     + " evt=" + evt);

        Iterator listeners = null;
        synchronized (providerPresenceStatusListeners)
        {
            listeners = new ArrayList(providerPresenceStatusListeners)
                .iterator();
        }

        while (listeners.hasNext())
        {
            ProviderPresenceStatusListener listener
                = (ProviderPresenceStatusListener) listeners.next();

            listener.providerStatusChanged(evt);
        }
    }
    /**
     * Notify all provider presence listeners that a new status message has
     * been set.
     * @param oldStatusMessage the status message our icq stack had so far
     * @param newStatusMessage the status message we have from now on
     */
    private void fireProviderStatusMessageChangeEvent(
                        String oldStatusMessage, String newStatusMessage)
    {

        PropertyChangeEvent evt = new PropertyChangeEvent(
                icqProvider, ProviderPresenceStatusListener.STATUS_MESSAGE,
                oldStatusMessage, newStatusMessage);

        logger.debug("Dispatching  stat. msg change. Listeners="
                     + providerPresenceStatusListeners.size()
                     + " evt=" + evt);

        Iterator listeners = null;
        synchronized (providerPresenceStatusListeners)
        {
            listeners = new ArrayList(providerPresenceStatusListeners).iterator();
        }

        while (listeners.hasNext())
        {
            ProviderPresenceStatusListener listener
                = (ProviderPresenceStatusListener) listeners.next();

            listener.providerStatusMessageChanged(evt);
        }
    }

    /**
     * Notify all subscription listeners of the corresponding event.
     *
     * @param eventID the int ID of the event to dispatch
     * @param sourceContact the ContactIcqImpl instance that this event is
     * pertaining to.
     * @param parentGroup the ContactGroupIcqImpl under which the corresponding
     * subscription is located.
     */
    void fireSubscriptionEvent( int eventID,
                                ContactIcqImpl sourceContact,
                                ContactGroupIcqImpl parentGroup)
    {
        SubscriptionEvent evt =
            new SubscriptionEvent(sourceContact, icqProvider, parentGroup,
                                  eventID);

        logger.debug("Dispatching a Subscription Event to"
                     +subscriptionListeners.size() + " listeners. Evt="+evt);

        Iterator listeners = null;
        synchronized (subscriptionListeners)
        {
            listeners = new ArrayList(subscriptionListeners).iterator();
        }

        while (listeners.hasNext())
        {
            SubscriptionListener listener
                = (SubscriptionListener) listeners.next();

            if (evt.getEventID() == SubscriptionEvent.SUBSCRIPTION_CREATED)
            {
                listener.subscriptionCreated(evt);
            }
            else if (evt.getEventID() == SubscriptionEvent.SUBSCRIPTION_REMOVED)
            {
                listener.subscriptionRemoved(evt);
            }
            else if (evt.getEventID() == SubscriptionEvent.SUBSCRIPTION_FAILED)
            {
                listener.subscriptionFailed(evt);
            }
        }
    }

    /**
     * Notify all subscription listeners of the corresponding contact property
     * change event.
     *
     * @param eventID the String ID of the event to dispatch
     * @param sourceContact the ContactIcqImpl instance that this event is
     * pertaining to.
     * @param oldValue the value that the changed property had before the change
     * occurred.
     * @param newValue the value that the changed property currently has (after
     * the change has occurred).
     */
    void fireContactPropertyChangeEvent( String               eventID,
                                         ContactIcqImpl       sourceContact,
                                         Object               oldValue,
                                         Object               newValue)
    {
        ContactPropertyChangeEvent evt =
            new ContactPropertyChangeEvent(sourceContact, eventID
                                  , oldValue, newValue);

        logger.debug("Dispatching a Contact Property Change Event to"
                     +subscriptionListeners.size() + " listeners. Evt="+evt);

        Iterator listeners = null;

        synchronized (subscriptionListeners)
        {
            listeners = new ArrayList(subscriptionListeners).iterator();
        }

        while (listeners.hasNext())
        {
            SubscriptionListener listener
                = (SubscriptionListener) listeners.next();

            listener.contactModified(evt);
        }
    }


    /**
     * Notify all subscription listeners of the corresponding event.
     *
     * @param sourceContact the ContactIcqImpl instance that this event is
     * pertaining to.
     * @param oldParentGroup the group that was previously a parent of the
     * source contact.
     * @param newParentGroup the group under which the corresponding
     * subscription is currently located.
     */
    void fireSubscriptionMovedEvent( ContactIcqImpl sourceContact,
                                     ContactGroupIcqImpl oldParentGroup,
                                     ContactGroupIcqImpl newParentGroup)
    {
        SubscriptionMovedEvent evt =
            new SubscriptionMovedEvent(sourceContact, icqProvider
                                       , oldParentGroup, newParentGroup);

        logger.debug("Dispatching a Subscription Event to"
                     +subscriptionListeners.size() + " listeners. Evt="+evt);

        Iterator listeners = null;
        synchronized (subscriptionListeners)
        {
            listeners = new ArrayList(subscriptionListeners).iterator();
        }

        while (listeners.hasNext())
        {
            SubscriptionListener listener
                = (SubscriptionListener) listeners.next();

            listener.subscriptionMoved(evt);
        }
    }


    /**
     * Notify all contact presence listeners of the corresponding event change
     * @param contact the contact that changed its status
     * @param oldStatus the status that the specified contact had so far
     * @param newStatus the status that the specified contact is currently in.
     * @param parentGroup the group containing the contact which caused the event
     */
    private void fireContactPresenceStatusChangeEvent(
                        Contact contact,
                        ContactGroup parentGroup,
                        PresenceStatus oldStatus,
                        PresenceStatus newStatus)
    {
        ContactPresenceStatusChangeEvent evt =
            new ContactPresenceStatusChangeEvent(
                contact, icqProvider, parentGroup, oldStatus, newStatus);

        logger.debug("Dispatching Contact Status Change. Listeners="
                     + contactPresenceStatusListeners.size()
                     + " evt=" + evt);

        Iterator listeners = null;
        synchronized (contactPresenceStatusListeners)
        {
            listeners = new ArrayList(contactPresenceStatusListeners).iterator();
        }

        while (listeners.hasNext())
        {
            ContactPresenceStatusListener listener
                = (ContactPresenceStatusListener) listeners.next();

            listener.contactPresenceStatusChanged(evt);
        }
    }

    /**
     * Our listener that will tell us when we're registered to icq and joust
     * sim is ready to accept us as a listener.
     */
    private class RegistrationStateListener
        implements RegistrationStateChangeListener
    {
        /**
         * The method is called by a ProtocolProvider implementation whenver
         * a change in the registration state of the corresponding provider had
         * occurred.
         * @param evt ProviderStatusChangeEvent the event describing the status
         * change.
         */
        public void registrationStateChanged(RegistrationStateChangeEvent evt)
        {
            logger.debug("The ICQ provider changed state from: "
                         + evt.getOldState()
                         + " to: " + evt.getNewState());
            if(evt.getNewState() == RegistrationState.REGISTERED)
            {
                logger.debug("adding a Bos Service Listener");
                icqProvider.getAimConnection().getBosService()
                    .addMainBosServiceListener(joustSimBosListener);

                ssContactList.init(
                    icqProvider.getAimConnection().getSsiService());

//                /**@todo implement the following
                 icqProvider.getAimConnection().getBuddyService()
                     .addBuddyListener(joustSimBuddySerListener);

//                  @todo we really need this for following the status of our
//                 contacts and we really need it here ...*/
                icqProvider.getAimConnection().getBuddyInfoManager()
                    .addGlobalBuddyInfoListener(new GlobalBuddyInfoListener());
                
                icqProvider.getAimConnection().getExternalServiceManager().
                    getIconServiceArbiter().addIconRequestListener(
                        new IconUpdateListener());
                
                if(icqProvider.USING_ICQ)
                {
                    opSetExtendedAuthorizations = 
                        (OperationSetExtendedAuthorizationsIcqImpl)
                            icqProvider.getSupportedOperationSets()
                            .get(OperationSetExtendedAuthorizations.class.getName());
                
                    if(presenceQueryTimer == null)
                        presenceQueryTimer = new Timer();
                    else
                    {
                        // cancel any previous jobs and create new timer
                        presenceQueryTimer.cancel();
                        presenceQueryTimer = new Timer();
                    }

                    AwaitingAuthorizationContactsPresenceTimer
                        queryTask = new AwaitingAuthorizationContactsPresenceTimer();

                    // start after 15 seconds. wait for login to be completed and 
                    // list and statuses to be gathered
                    presenceQueryTimer.scheduleAtFixedRate(
                            queryTask, 15000, PRESENCE_QUERY_INTERVAL);
                }
            }
            else if(evt.getNewState() == RegistrationState.UNREGISTERED
                 || evt.getNewState() == RegistrationState.AUTHENTICATION_FAILED
                 || evt.getNewState() == RegistrationState.CONNECTION_FAILED)
            {
                //since we are disconnected, we won't receive any further status
                //updates so we need to change by ourselves our own status as
                //well as set to offline all contacts in our contact list that
                //were online

                //start by notifying that our own status has changed
                long oldStatus  = currentIcqStatus;
                currentIcqStatus = -1;

                //only notify of an event change if there was really one.
                if( oldStatus != -1 )
                    fireProviderPresenceStatusChangeEvent(oldStatus,
                                                          currentIcqStatus);

                //send event notifications saying that all our buddies are
                //offline. The icq protocol does not implement top level buddies
                //nor subgroups for top level groups so a simple nested loop
                //would be enough.
                Iterator groupsIter = getServerStoredContactListRoot()
                                                                .subgroups();
                while(groupsIter.hasNext())
                {
                    ContactGroupIcqImpl group
                        = (ContactGroupIcqImpl)groupsIter.next();

                    Iterator contactsIter = group.contacts();

                    while(contactsIter.hasNext())
                    {
                        ContactIcqImpl contact
                            = (ContactIcqImpl)contactsIter.next();

                        PresenceStatus oldContactStatus
                            = contact.getPresenceStatus();

                        if(!oldContactStatus.isOnline())
                            continue;

                        contact.updatePresenceStatus(IcqStatusEnum.OFFLINE);

                        fireContactPresenceStatusChangeEvent(
                              contact
                            , contact.getParentContactGroup()
                            , oldContactStatus, IcqStatusEnum.OFFLINE);
                    }
                }
            }
        }

    }

    /**
     * The listeners that would monitor the joust sim stack for changes in our
     * own presence status.
     */
    private class JoustSimBosListener implements MainBosServiceListener
    {
        /**
         * Notifications of exta information such as avail message, icon hash
         * or certificate.
         * @param extraInfos List
         */
        public void handleYourExtraInfo(List extraInfos)
        {
            logger.debug("Got extra info: " + extraInfos);
            // @xxx we should one day probably do something here, like check
            // whether the status message has been changed for example.
            for (int i = 0; i < extraInfos.size(); i ++){
                ExtraInfoBlock block = (ExtraInfoBlock) extraInfos.get(i);
                if (block.getType() == ExtraInfoBlock.TYPE_AVAILMSG){
                    String statusMessage = ExtraInfoData.readAvailableMessage(
                                                    block.getExtraData());
                    logger.debug("Received a status message:" + statusMessage);

                    if ( getCurrentStatusMessage().equals(statusMessage)){
                        logger.debug("Status message is same as old. Ignoring");
                        return;
                    }

                    String oldStatusMessage = getCurrentStatusMessage();
                    currentStatusMessage = statusMessage;

                    fireProviderStatusMessageChangeEvent(
                        oldStatusMessage, getCurrentStatusMessage());
                }
            }
        }

        /**
         * Fires the corresponding presence status chagne event. Note that this
         * method will be called once per sendSnac packet. When setting a new
         * status we generally send three packets - 1 for the status and 2 for
         * the status message. Make sure that only one event goes outside of
         * this package.
         *
         * @param service the source MainBosService instance
         * @param userInfo our own info
         */
        public void handleYourInfo(MainBosService service,
                                   FullUserInfo userInfo)
        {
            logger.debug("Received our own user info: " + userInfo);
            logger.debug("previous status was: " + currentIcqStatus);
            logger.debug("new status is: " + userInfo.getIcqStatus());

            //update the last received field.
            long oldStatus  = currentIcqStatus;
            
            if(icqProvider.USING_ICQ)
            {
                currentIcqStatus = userInfo.getIcqStatus();

                //it might happen that the info here is -1 (in case we're going back
                //to online). Yet the fact that we're getting the event means
                //that we're very much online so make sure we change accordingly
                if (currentIcqStatus == -1 )
                    currentIcqStatus = ICQ_ONLINE_MASK;

                //only notify of an event change if there was really one.
                if( oldStatus !=  currentIcqStatus)
                    fireProviderPresenceStatusChangeEvent(oldStatus,
                                                        currentIcqStatus);
            }
            else
            {
                if(userInfo.getAwayStatus() != null && userInfo.getAwayStatus().equals(Boolean.TRUE))
                {
                    currentIcqStatus = presenceStatusToIcqStatusLong(IcqStatusEnum.AWAY);
                }
                else if(userInfo.getIcqStatus() != -1)
                {
                    currentIcqStatus = userInfo.getIcqStatus();
                }
                else // online status
                    currentIcqStatus = ICQ_ONLINE_MASK;                
                
                if( oldStatus != currentIcqStatus )
                    fireProviderPresenceStatusChangeEvent(oldStatus,
                                                        currentIcqStatus);
            }
        }
    }

    /**
     * Listens for status updates coming from the joust sim statck and generates
     * the corresponding sip-communicator events.
     * @author Emil Ivov
     */
    private class JoustSimBuddyServiceListener implements BuddyServiceListener
    {

        /**
         * Updates the last received status in the corresponding contact
         * and fires a contact presence status change event.
         * @param service the BuddyService that generated the exception
         * @param buddy the Screenname of the buddy whose status update we're
         * receiving
         * @param info the FullUserInfo containing the new status of the
         * corresponding contact
         */
        public void gotBuddyStatus(BuddyService service, Screenname buddy,
                                   FullUserInfo info)
        {
            logger.debug("Received a status update for buddy=" + buddy);
            logger.debug("Updated user info is " + info);

            ContactIcqImpl sourceContact
                = ssContactList.findContactByScreenName(buddy.getFormatted());

            if(sourceContact == null){
                logger.warn("No source contact found for screenname=" + buddy);
                return;
            }
            PresenceStatus oldStatus
                = sourceContact.getPresenceStatus();
            
            PresenceStatus newStatus = null;
            
            if(!icqProvider.USING_ICQ)
            {
                Boolean awayStatus = info.getAwayStatus();
                if(awayStatus == null || awayStatus.equals(Boolean.FALSE))
                    newStatus = IcqStatusEnum.ONLINE;
                else
                    newStatus = IcqStatusEnum.AWAY;
            }
            else
                newStatus = icqStatusLongToPresenceStatus(info.getIcqStatus());
            
            sourceContact.updatePresenceStatus(newStatus);

            ContactGroupIcqImpl parent
                = ssContactList.findContactGroup(sourceContact);

            logger.debug("Will Dispatch the contact status event.");
            fireContactPresenceStatusChangeEvent(sourceContact, parent,
                                                 oldStatus, newStatus);

            List extraInfoBlocks = info.getExtraInfoBlocks();
            if(extraInfoBlocks != null){
                for (int i = 0; i < extraInfoBlocks.size(); i++)
                {
                    ExtraInfoBlock block
                        = ( (ExtraInfoBlock) extraInfoBlocks.get(i));
                    if (block.getType() == ExtraInfoBlock.TYPE_AVAILMSG)
                    {
                        String status = ExtraInfoData.readAvailableMessage(
                            block.getExtraData());
                        logger.info("Status Message is: " + status + ".");
                    }
                }
            }
        }

        /**
         * Updates the last received status in the corresponding contact
         * and fires a contact presence status change event.
         *
         * @param service the BuddyService that generated the exception
         * @param buddy the Screenname of the buddy whose status update we're
         * receiving
         */
        public void buddyOffline(BuddyService service, Screenname buddy)
        {
            logger.debug("Received a status update for buddy=" + buddy);

            ContactIcqImpl sourceContact
                = ssContactList.findContactByScreenName(buddy.getFormatted());

            if(sourceContact == null)
                return;

            PresenceStatus oldStatus
                = sourceContact.getPresenceStatus();
            PresenceStatus newStatus = IcqStatusEnum.OFFLINE;

            sourceContact.updatePresenceStatus(newStatus);

            ContactGroupIcqImpl parent
                = ssContactList.findContactGroup(sourceContact);

            fireContactPresenceStatusChangeEvent(sourceContact, parent,
                                                 oldStatus, newStatus);
        }
    }

    /**
     * Apart from loggin - does nothing so far.
     */
    private class GlobalBuddyInfoListener extends GlobalBuddyInfoAdapter{
        public void receivedStatusUpdate(BuddyInfoManager manager,
                                         Screenname buddy, BuddyInfo info)
        {
            logger.debug("buddy=" + buddy);
            logger.debug("info.getAwayMessage()=" + info.getAwayMessage());
            logger.debug("info.getOnlineSince()=" + info.getOnlineSince());
            logger.debug("info.getStatusMessage()=" + info.getStatusMessage());
        }

    }

    private class AuthListener
        implements BuddyAuthorizationListener
    {
        public void authorizationDenied(Screenname screenname, String reason)
        {
            logger.trace("authorizationDenied from " + screenname);
            Contact srcContact = findContactByID(screenname.getFormatted());

            authorizationHandler.processAuthorizationResponse(
                new AuthorizationResponse(AuthorizationResponse.REJECT, reason)
                , srcContact);
            try
            {
                unsubscribe(srcContact);
            } catch (OperationFailedException ex)
            {
                logger.error("cannot remove denied contact : " + srcContact, ex);
            }
        }

        public void authorizationAccepted(Screenname screenname, String reason)
        {
            logger.trace("authorizationAccepted from " + screenname);
            Contact srcContact = findContactByID(screenname.getFormatted());
            ssContactList.moveAwaitingAuthorizationContact(
                (ContactIcqImpl)srcContact);

            authorizationHandler.processAuthorizationResponse(
                new AuthorizationResponse(AuthorizationResponse.ACCEPT, reason)
                , srcContact);
        }

        public void authorizationRequestReceived(Screenname screenname,
                                                 String reason)
        {
            logger.trace("authorizationRequestReceived from " + screenname);
            Contact srcContact = findContactByID(screenname.getFormatted());

            if(srcContact == null)
                srcContact = createVolatileContact(screenname.getFormatted());

            AuthorizationRequest authRequest = new AuthorizationRequest();
                authRequest.setReason(reason);

            AuthorizationResponse authResponse =
                authorizationHandler.processAuthorisationRequest(
                    authRequest, srcContact);


            if (authResponse.getResponseCode() == AuthorizationResponse.IGNORE)
                return;

            icqProvider.getAimConnection().getSsiService().
                replyBuddyAuthorization(
                    screenname,
                    authResponse.getResponseCode() == AuthorizationResponse.ACCEPT,
                    authResponse.getReason());
        }

        public boolean authorizationRequired(Screenname screenname, Group parentGroup)
        {
            logger.trace("authorizationRequired from " + screenname);

            logger.trace("finding buddy : " + screenname);
            ContactIcqImpl srcContact = 
                ssContactList.findContactByScreenName(screenname.getFormatted());

            if(srcContact == null)
            {
                ContactGroupIcqImpl parent = 
                    ssContactList.findContactGroup(parentGroup);
                srcContact = ssContactList.
                    createUnresolvedContact((ContactGroupIcqImpl)parent, screenname);
                
                Buddy buddy = ((ContactIcqImpl)srcContact).getJoustSimBuddy();
                
                if(buddy instanceof VolatileBuddy)
                    ((VolatileBuddy)buddy).setAwaitingAuthorization(true);
                
                 ContactGroupIcqImpl theAwaitingAuthorizationGroup = 
                     ssContactList.findContactGroup(ssContactList.awaitingAuthorizationGroupName);   
                 
                 
                if(theAwaitingAuthorizationGroup == null)
                {
                    List emptyBuddies = new LinkedList();
                    theAwaitingAuthorizationGroup = new ContactGroupIcqImpl(
                        new VolatileGroup(ssContactList.awaitingAuthorizationGroupName), 
                        emptyBuddies, ssContactList, false);

                    ((RootContactGroupIcqImpl)ssContactList.getRootGroup()).
                        addSubGroup(theAwaitingAuthorizationGroup);

                    ssContactList.fireGroupEvent(theAwaitingAuthorizationGroup
                        , ServerStoredGroupEvent.GROUP_CREATED_EVENT);
                }
                 
                 
                 ((ContactGroupIcqImpl)parent).removeContact(srcContact);
                 theAwaitingAuthorizationGroup.addContact(srcContact);
                 
                 Object lock = new Object();
                 synchronized(lock){
                     try{ lock.wait(500); }catch(Exception e){}
                 };
                 
                 fireSubscriptionMovedEvent(srcContact, 
                     parent, theAwaitingAuthorizationGroup);
            }

            AuthorizationRequest authRequest =
                authorizationHandler.createAuthorizationRequest(
                srcContact);

            if(authRequest == null)
                return false;

            icqProvider.getAimConnection().getSsiService().
                sendFutureBuddyAuthorization(screenname, authRequest.getReason());

            icqProvider.getAimConnection().getSsiService().
                requestBuddyAuthorization(screenname, authRequest.getReason());

            return true;
        }

        public void futureAuthorizationGranted(Screenname screenname,
                                               String reason)
        {
            logger.trace("futureAuthorizationGranted from " + screenname);
        }

        public void youWereAdded(Screenname screenname)
        {
            logger.trace("youWereAdded from " + screenname);
        }
    }
    
    /**
     * Notified if buddy icon is changed
     */
    private class IconUpdateListener
        implements IconRequestListener
    {
        public void buddyIconCleared(IconService iconService, Screenname screenname, ExtraInfoData extraInfoData)
        {
            updateBuddyyIcon(screenname, null);
        }

        public void buddyIconUpdated(IconService iconService, Screenname screenname, ExtraInfoData extraInfoData, ByteBlock byteBlock)
        {
            if(byteBlock != null)
                updateBuddyyIcon(screenname, byteBlock.toByteArray());
        }
        
        /**
         * Changes the Contact image
         * @param screenname the contact screenname
         * @param icon byte array representing the image
         */
        private void updateBuddyyIcon(Screenname screenname, byte[] icon)
        {
            ContactIcqImpl contact = 
                ssContactList.findContactByScreenName(screenname.getFormatted());
            
            if(contact != null)
               contact.setImage(icon);
        }
    }
    
    private class AwaitingAuthorizationContactsPresenceTimer
        extends TimerTask
    {
        public void run()
        {
            logger.trace("Running status retreiver for AwaitingAuthorizationContacts");
            
            ContactGroupIcqImpl theAwaitingAuthorizationGroup = 
                ssContactList.findContactGroup(
                    ssContactList.awaitingAuthorizationGroupName);
            
            if(theAwaitingAuthorizationGroup == null)
                return;
            
            Iterator iter = theAwaitingAuthorizationGroup.contacts();
            while (iter.hasNext())
            {
                ContactIcqImpl sourceContact = (ContactIcqImpl)iter.next();

                PresenceStatus newStatus = queryContactStatus(sourceContact.getAddress());

                PresenceStatus oldStatus
                    = sourceContact.getPresenceStatus();
                
                if(newStatus.equals(oldStatus))
                   continue; 

                sourceContact.updatePresenceStatus(newStatus);

                ContactGroupIcqImpl parent
                    = ssContactList.findContactGroup(sourceContact);

                fireContactPresenceStatusChangeEvent(sourceContact, theAwaitingAuthorizationGroup,
                                                 oldStatus, newStatus);
                
                if( !newStatus.equals(IcqStatusEnum.OFFLINE) &&
                    !buddiesSeenAvailable.contains(sourceContact.getAddress()))
                {
                    buddiesSeenAvailable.add(sourceContact.getAddress());
                    try
                    {
                        AuthorizationRequest req = new AuthorizationRequest();
                        req.setReason("I'm resending my request. Please authorize me!");
                        
                        opSetExtendedAuthorizations.reRequestAuthorization(req, sourceContact);
                    } catch (OperationFailedException ex)
                    {
                        logger.error("failed to reRequestAuthorization", ex);
                    }
                }   
            }           
        }
    }
}