/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.protocol.amqp.connect.mirror;

import java.util.Collection;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

import org.apache.activemq.artemis.api.core.ActiveMQAddressDoesNotExistException;
import org.apache.activemq.artemis.api.core.ActiveMQNonExistentQueueException;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.io.IOCallback;
import org.apache.activemq.artemis.core.io.RunnableCallback;
import org.apache.activemq.artemis.core.paging.cursor.PagedReference;
import org.apache.activemq.artemis.core.persistence.OperationContext;
import org.apache.activemq.artemis.core.persistence.impl.journal.OperationContextImpl;
import org.apache.activemq.artemis.core.postoffice.Binding;
import org.apache.activemq.artemis.core.postoffice.Bindings;
import org.apache.activemq.artemis.core.postoffice.DuplicateIDCache;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.impl.AddressInfo;
import org.apache.activemq.artemis.core.server.impl.RoutingContextImpl;
import org.apache.activemq.artemis.core.server.mirror.MirrorController;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.core.transaction.TransactionOperationAbstract;
import org.apache.activemq.artemis.core.transaction.impl.TransactionImpl;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessageBrokerAccessor;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPSessionCallback;
import org.apache.activemq.artemis.protocol.amqp.broker.ProtonProtocolManager;
import org.apache.activemq.artemis.protocol.amqp.proton.AMQPConnectionContext;
import org.apache.activemq.artemis.protocol.amqp.proton.AMQPSessionContext;
import org.apache.activemq.artemis.protocol.amqp.proton.AMQPTunneledCoreLargeMessageReader;
import org.apache.activemq.artemis.protocol.amqp.proton.AMQPTunneledCoreMessageReader;
import org.apache.activemq.artemis.protocol.amqp.proton.MessageReader;
import org.apache.activemq.artemis.protocol.amqp.proton.ProtonAbstractReceiver;
import org.apache.activemq.artemis.utils.ByteUtil;
import org.apache.activemq.artemis.utils.pools.MpscPool;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.DeliveryAnnotations;
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.engine.Receiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.invoke.MethodHandles;

import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.ADDRESS;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.ADD_ADDRESS;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.BROKER_ID;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.CREATE_QUEUE;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.DELETE_ADDRESS;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.DELETE_QUEUE;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.EVENT_TYPE;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.INTERNAL_BROKER_ID_EXTRA_PROPERTY;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.INTERNAL_DESTINATION;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.INTERNAL_ID;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.POST_ACK;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.QUEUE;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.INTERNAL_ID_EXTRA_PROPERTY;
import static org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource.TARGET_QUEUES;
import static org.apache.activemq.artemis.protocol.amqp.proton.AMQPTunneledMessageConstants.AMQP_TUNNELED_CORE_LARGE_MESSAGE_FORMAT;
import static org.apache.activemq.artemis.protocol.amqp.proton.AMQPTunneledMessageConstants.AMQP_TUNNELED_CORE_MESSAGE_FORMAT;

public class AMQPMirrorControllerTarget extends ProtonAbstractReceiver implements MirrorController {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final ThreadLocal<MirrorController> CONTROLLER_THREAD_LOCAL = new ThreadLocal<>();

   public static void setControllerInUse(MirrorController controller) {
      CONTROLLER_THREAD_LOCAL.set(controller);
   }

   public static MirrorController getControllerInUse() {
      return CONTROLLER_THREAD_LOCAL.get();
   }

   /**
    * Objects of this class can be used by either transaction or by OperationContext.
    * It is important that when you're using the transactions you clear any references to
    * the operation context. Don't use transaction and OperationContext at the same time
    * as that would generate duplicates on the objects cache.
    */
   class ACKMessageOperation implements IOCallback, Runnable {

      Delivery delivery;

      /**
       * notice that when you use the Transaction, you need to make sure you don't use the IO
       */
      public TransactionOperationAbstract tx = new TransactionOperationAbstract() {
         @Override
         public void afterCommit(Transaction tx) {
            connectionRun();
         }
      };

      void reset() {
         this.delivery = null;
      }

      ACKMessageOperation setDelivery(Delivery delivery) {
         this.delivery = delivery;
         return this;
      }

      @Override
      public void run() {
         if (!connection.isHandler()) {
            logger.info("Moving execution to proton handler");
            connectionRun();
            return;
         }
         logger.trace("Delivery settling for {}, context={}", delivery, delivery.getContext());
         delivery.disposition(Accepted.getInstance());
         settle(delivery);
         connection.flush();
         AMQPMirrorControllerTarget.this.ackMessageMpscPool.release(ACKMessageOperation.this);
      }

      @Override
      public void done() {
         connectionRun();
      }

      public void connectionRun() {
         connection.runNow(ACKMessageOperation.this);
      }

      @Override
      public void onError(int errorCode, String errorMessage) {
         logger.warn("{}-{}", errorCode, errorMessage);
      }
   }

   // in a regular case we should not have more than amqpCredits on the pool, that's the max we would need
   private final MpscPool<ACKMessageOperation> ackMessageMpscPool = new MpscPool<>(connection.getAmqpCredits(), ACKMessageOperation::reset, ACKMessageOperation::new);

   final RoutingContextImpl routingContext = new RoutingContextImpl(null);

   final BasicMirrorController<Receiver> basicController;

   final ActiveMQServer server;

   DuplicateIDCache lruduplicateIDCache;
   String lruDuplicateIDKey;

   private final ReferenceIDSupplier referenceNodeStore;

   OperationContext mirrorContext;

   private MessageReader coreMessageReader;

   private MessageReader coreLargeMessageReader;

   public AMQPMirrorControllerTarget(AMQPSessionCallback sessionSPI,
                                     AMQPConnectionContext connection,
                                     AMQPSessionContext protonSession,
                                     Receiver receiver,
                                     ActiveMQServer server) {
      super(sessionSPI, connection, protonSession, receiver);
      this.basicController = new BasicMirrorController(server);
      this.basicController.setLink(receiver);
      this.server = server;
      this.referenceNodeStore = sessionSPI.getProtocolManager().getReferenceIDSupplier();
      mirrorContext = protonSession.getSessionSPI().getSessionContext();
   }

   @Override
   public String getRemoteMirrorId() {
      return basicController.getRemoteMirrorId();
   }

   @Override
   public void flow() {
      creditRunnable.run();
   }

   @Override
   protected void actualDelivery(Message message, Delivery delivery, DeliveryAnnotations deliveryAnnotations, Receiver receiver, Transaction tx) {
      recoverContext();
      incrementSettle();

      logger.trace("{}::actualdelivery call for {}", server, message);
      setControllerInUse(this);

      delivery.setContext(message);

      ACKMessageOperation messageAckOperation = this.ackMessageMpscPool.borrow().setDelivery(delivery);

      try {

         if (message instanceof AMQPMessage) {
            final AMQPMessage amqpMessage = (AMQPMessage) message;

            /** We use message annotations, because on the same link we will receive control messages
             *  coming from mirror events,
             *  and the actual messages that need to be replicated.
             *  Using anything from the body would force us to parse the body on regular messages.
             *  The body of the message may still be used on control messages, on cases where a JSON string is sent. */
            Object eventType = AMQPMessageBrokerAccessor.getMessageAnnotationProperty(amqpMessage, EVENT_TYPE);
            if (eventType != null) {
               if (eventType.equals(ADD_ADDRESS)) {
                  AddressInfo addressInfo = parseAddress(amqpMessage);

                  addAddress(addressInfo);
               } else if (eventType.equals(DELETE_ADDRESS)) {
                  AddressInfo addressInfo = parseAddress(amqpMessage);

                  deleteAddress(addressInfo);
               } else if (eventType.equals(CREATE_QUEUE)) {
                  QueueConfiguration queueConfiguration = parseQueue(amqpMessage);

                  createQueue(queueConfiguration);
               } else if (eventType.equals(DELETE_QUEUE)) {
                  String address = (String) AMQPMessageBrokerAccessor.getMessageAnnotationProperty(amqpMessage, ADDRESS);
                  String queueName = (String) AMQPMessageBrokerAccessor.getMessageAnnotationProperty(amqpMessage, QUEUE);

                  deleteQueue(SimpleString.toSimpleString(address), SimpleString.toSimpleString(queueName));
               } else if (eventType.equals(POST_ACK)) {
                  String nodeID = (String) AMQPMessageBrokerAccessor.getMessageAnnotationProperty(amqpMessage, BROKER_ID);

                  AckReason ackReason = AMQPMessageBrokerAccessor.getMessageAnnotationAckReason(amqpMessage);

                  if (nodeID == null) {
                     nodeID = getRemoteMirrorId(); // not sending the nodeID means it's data generated on that broker
                  }
                  String queueName = (String) AMQPMessageBrokerAccessor.getMessageAnnotationProperty(amqpMessage, QUEUE);
                  AmqpValue value = (AmqpValue) amqpMessage.getBody();
                  Long messageID = (Long) value.getValue();
                  if (postAcknowledge(queueName, nodeID, messageID, messageAckOperation, ackReason)) {
                     messageAckOperation = null;
                  }
               }
            } else {
               if (sendMessage(amqpMessage, deliveryAnnotations, messageAckOperation)) {
                  // since the send was successful, we give up the reference here,
                  // so there won't be any call on afterCompleteOperations
                  messageAckOperation = null;
               }
            }
         } else {
            if (sendMessage(message, deliveryAnnotations, messageAckOperation)) {
               // since the send was successful, we give up the reference here,
               // so there won't be any call on afterCompleteOperations
               messageAckOperation = null;
            }
         }
      } catch (Throwable e) {
         logger.warn(e.getMessage(), e);
      } finally {
         setControllerInUse(null);
         if (messageAckOperation != null) {
            server.getStorageManager().afterCompleteOperations(messageAckOperation);
         }
      }
   }

   @Override
   public void initialize() throws Exception {
      initialized = true;

      // Match the settlement mode of the remote instead of relying on the default of MIXED.
      receiver.setSenderSettleMode(receiver.getRemoteSenderSettleMode());

      // We don't currently support SECOND so enforce that the answer is anlways FIRST
      receiver.setReceiverSettleMode(ReceiverSettleMode.FIRST);

      flow();
   }

   @Override
   protected MessageReader trySelectMessageReader(Receiver receiver, Delivery delivery) {
      if (delivery.getMessageFormat() == AMQP_TUNNELED_CORE_MESSAGE_FORMAT) {
         return coreMessageReader != null ?
            coreMessageReader : (coreMessageReader = new AMQPTunneledCoreMessageReader(this));
      } else if (delivery.getMessageFormat() == AMQP_TUNNELED_CORE_LARGE_MESSAGE_FORMAT) {
         return coreLargeMessageReader != null ?
            coreLargeMessageReader : (coreLargeMessageReader = new AMQPTunneledCoreLargeMessageReader(this));
      } else {
         return super.trySelectMessageReader(receiver, delivery);
      }
   }

   private QueueConfiguration parseQueue(AMQPMessage message) {
      AmqpValue bodyValue = (AmqpValue) message.getBody();
      String body = (String) bodyValue.getValue();
      return QueueConfiguration.fromJSON(body);
   }

   private AddressInfo parseAddress(AMQPMessage message) {
      AmqpValue bodyValue = (AmqpValue) message.getBody();
      String body = (String) bodyValue.getValue();
      return AddressInfo.fromJSON(body);
   }

   @Override
   public void preAcknowledge(Transaction tx, MessageReference ref, AckReason reason) throws Exception {
      // NO-OP
   }

   @Override
   public void addAddress(AddressInfo addressInfo) throws Exception {
      logger.debug("{} adding address {}", server, addressInfo);

      server.addAddressInfo(addressInfo);
   }

   @Override
   public void deleteAddress(AddressInfo addressInfo) throws Exception {
      logger.debug("{} delete address {}", server, addressInfo);

      try {
         server.removeAddressInfo(addressInfo.getName(), null, true);
      } catch (ActiveMQAddressDoesNotExistException expected) {
         // it was removed from somewhere else, which is fine
         logger.debug(expected.getMessage(), expected);
      } catch (Exception e) {
         logger.warn(e.getMessage(), e);
      }
   }

   @Override
   public void createQueue(QueueConfiguration queueConfiguration) throws Exception {
      logger.debug("{} adding queue {}", server, queueConfiguration);

      try {
         server.createQueue(queueConfiguration, true);
      } catch (Exception e) {
         logger.debug("Queue could not be created, already existed {}", queueConfiguration, e);
      }
   }

   @Override
   public void deleteQueue(SimpleString addressName, SimpleString queueName) throws Exception {
      if (logger.isDebugEnabled()) {
         logger.debug("{} destroy queue {} on address = {} server {}", server, queueName, addressName, server.getIdentity());
      }
      try {
         server.destroyQueue(queueName, null, false, true, false, false);
      } catch (ActiveMQNonExistentQueueException expected) {
         if (logger.isDebugEnabled()) {
            logger.debug("{} queue {} was previously removed", server, queueName, expected);
         }
      }
   }

   public boolean postAcknowledge(String queue,
                                  String nodeID,
                                  long messageID,
                                  ACKMessageOperation ackMessage,
                                  AckReason reason) throws Exception {
      final Queue targetQueue = server.locateQueue(queue);

      if (targetQueue == null) {
         logger.warn("Queue {} not found on mirror target, ignoring ack for queue={}, messageID={}, nodeID={}", queue, queue, messageID, nodeID);
         return false;
      }

      if (logger.isDebugEnabled()) {
         // we only do the following check if debug
         if (targetQueue.getConsumerCount() > 0) {
            logger.debug("server {}, queue {} has consumers while delivering ack for {}", server.getIdentity(), targetQueue.getName(), messageID);
         }
      }

      if (logger.isTraceEnabled()) {
         logger.trace("Server {} with queue = {} being acked for {} coming from {} targetQueue = {}",
                      server.getIdentity(), queue, messageID, messageID, targetQueue);
      }

      performAck(nodeID, messageID, targetQueue, ackMessage, reason, (short)0);
      return true;
   }

   public void performAckOnPage(String nodeID, long messageID, Queue targetQueue, IOCallback ackMessageOperation) {
      PageAck pageAck = new PageAck(targetQueue, nodeID, messageID, ackMessageOperation);
      targetQueue.getPageSubscription().scanAck(pageAck, pageAck, pageAck, pageAck);
   }

   private void performAck(String nodeID, long messageID, Queue targetQueue, ACKMessageOperation ackMessageOperation, AckReason reason, final short retry) {

      if (logger.isTraceEnabled()) {
         logger.trace("performAck (nodeID={}, messageID={}), targetQueue={})", nodeID, messageID, targetQueue.getName());
      }

      MessageReference reference = targetQueue.removeWithSuppliedID(nodeID, messageID, referenceNodeStore);

      if (reference == null) {
         if (logger.isDebugEnabled()) {
            logger.debug("Retrying Reference not found on messageID={}, nodeID={}, queue={}. currentRetry={}", messageID, nodeID, targetQueue, retry);
         }
         switch (retry) {
            case 0:
               // first retry, after IO Operations
               sessionSPI.getSessionContext().executeOnCompletion(new RunnableCallback(() -> performAck(nodeID, messageID, targetQueue, ackMessageOperation, reason, (short) 1)));
               return;
            case 1:
               // second retry after the queue is flushed the temporary adds
               targetQueue.flushOnIntermediate(() -> {
                  recoverContext();
                  performAck(nodeID, messageID, targetQueue, ackMessageOperation, reason, (short)2);
               });
               return;
            case 2:
               // third retry, on paging
               if (reason != AckReason.EXPIRED) {
                  // if expired, we don't need to check on paging
                  // as the message will expire again when depaged (if on paging)
                  performAckOnPage(nodeID, messageID, targetQueue, ackMessageOperation);
                  return;
               } else {
                  connection.runNow(ackMessageOperation);
               }
         }
      }

      if (reference != null) {
         if (logger.isTraceEnabled()) {
            logger.trace("Post ack Server {} worked well for messageID={} nodeID={} queue={}, targetQueue={}", server, messageID, nodeID, reference.getQueue(), targetQueue);
         }
         try {
            switch (reason) {
               case EXPIRED:
                  targetQueue.expire(reference, null, false);
                  break;
               default:
                  targetQueue.acknowledge(null, reference, reason, null, false);
                  break;
            }
            OperationContextImpl.getContext().executeOnCompletion(ackMessageOperation);
         } catch (Exception e) {
            logger.warn(e.getMessage(), e);
         }
      }
   }

   /**
    * this method returning true means the sendMessage was successful, and the IOContext should no longer be used.
    * as the sendMessage was successful the OperationContext of the transaction will take care of the completion.
    * The caller of this method should give up any reference to messageCompletionAck when this method returns true.
    * */
   private boolean sendMessage(Message message, DeliveryAnnotations deliveryAnnotations, ACKMessageOperation messageCompletionAck) throws Exception {
      if (message.getMessageID() <= 0) {
         message.setMessageID(server.getStorageManager().generateID());
      }

      String internalMirrorID = (String) deliveryAnnotations.getValue().get(BROKER_ID);
      if (internalMirrorID == null) {
         internalMirrorID = getRemoteMirrorId(); // not pasisng the ID means the data was generated on the remote broker
      }
      Long internalIDLong = (Long) deliveryAnnotations.getValue().get(INTERNAL_ID);
      String internalAddress = (String) deliveryAnnotations.getValue().get(INTERNAL_DESTINATION);

      Collection<String> targetQueues = (Collection<String>) deliveryAnnotations.getValue().get(TARGET_QUEUES);

      long internalID = 0;

      if (internalIDLong != null) {
         internalID = internalIDLong;
      }

      if (logger.isTraceEnabled()) {
         logger.trace("sendMessage on server {} for message {} with internalID = {} mirror id {}", server, message, internalIDLong, internalMirrorID);
      }

      routingContext.setDuplicateDetection(false); // we do our own duplicate detection here

      DuplicateIDCache duplicateIDCache;
      if (lruDuplicateIDKey != null && lruDuplicateIDKey.equals(internalMirrorID)) {
         duplicateIDCache = lruduplicateIDCache;
      } else {
         // we use the number of credits for the duplicate detection, as that means the maximum number of elements you can have pending
         logger.trace("Setting up duplicate detection cache on {}, ServerID={} with {} elements, being the number of credits", ProtonProtocolManager.MIRROR_ADDRESS, internalMirrorID, connection.getAmqpCredits());

         lruDuplicateIDKey = internalMirrorID;
         lruduplicateIDCache = server.getPostOffice().getDuplicateIDCache(SimpleString.toSimpleString(ProtonProtocolManager.MIRROR_ADDRESS + "_" + internalMirrorID), connection.getAmqpCredits());
         duplicateIDCache = lruduplicateIDCache;
      }

      byte[] duplicateIDBytes = ByteUtil.longToBytes(internalIDLong);

      if (duplicateIDCache.contains(duplicateIDBytes)) {
         flow();
         return false;
      }

      message.setBrokerProperty(INTERNAL_ID_EXTRA_PROPERTY, internalID);
      message.setBrokerProperty(INTERNAL_BROKER_ID_EXTRA_PROPERTY, internalMirrorID);

      if (internalAddress != null) {
         message.setAddress(internalAddress);
      }

      final TransactionImpl transaction = new MirrorTransaction(server.getStorageManager());
      transaction.addOperation(messageCompletionAck.tx);
      routingContext.setTransaction(transaction);
      duplicateIDCache.addToCache(duplicateIDBytes, transaction);

      routingContext.clear().setMirrorSource(this).setLoadBalancingType(MessageLoadBalancingType.LOCAL_ONLY);
      if (targetQueues != null) {
         targetQueuesRouting(message, routingContext, targetQueues);
         server.getPostOffice().processRoute(message, routingContext, false);
      } else {
         server.getPostOffice().route(message, routingContext, false);
      }
      // We use this as part of a transaction because of the duplicate detection cache that needs to be done atomically
      transaction.commit();
      flow();

      // return true here will instruct the caller to ignore any references to messageCompletionAck
      return true;
   }

   /** When the source mirror receives messages from a cluster member of his own, it should then fill targetQueues so we could play the same semantic the source applied on its routing */
   private void targetQueuesRouting(final Message message,
                                    final RoutingContext context,
                                    final Collection<String> queueNames) throws Exception {
      Bindings bindings = server.getPostOffice().getBindingsForAddress(message.getAddressSimpleString());
      queueNames.forEach(name -> {
         Binding binding = bindings.getBinding(name);
         if (binding != null) {
            try {
               binding.route(message, context);
            } catch (Exception e) {
               logger.warn(e.getMessage(), e);
            }
         }
      });
   }

   @Override
   public void postAcknowledge(MessageReference ref, AckReason reason) {
      // Do nothing
   }

   @Override
   public void sendMessage(Transaction tx, Message message, RoutingContext context) {
      // Do nothing
   }

   class PageAck implements ToIntFunction<PagedReference>, BooleanSupplier, Runnable {

      final Queue targetQueue;
      final String nodeID;
      final long messageID;
      final IOCallback operation;

      PageAck(Queue targetQueue, String nodeID, long messageID, IOCallback operation) {
         this.targetQueue = targetQueue;
         this.nodeID = nodeID;
         this.messageID = messageID;
         this.operation = operation;
      }

      /**
       * Method to retry the ack before a scan
       */
      @Override
      public boolean getAsBoolean() {
         try {
            recoverContext();
            MessageReference reference = targetQueue.removeWithSuppliedID(nodeID, messageID, referenceNodeStore);
            if (reference == null) {
               return false;
            } else {
               targetQueue.acknowledge(null, reference, AckReason.NORMAL, null, false);
               OperationContextImpl.getContext().executeOnCompletion(operation);
               return true;
            }
         } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
            return false;
         }
      }

      @Override
      public int applyAsInt(PagedReference reference) {
         String refNodeID = referenceNodeStore.getServerID(reference);
         long refMessageID = referenceNodeStore.getID(reference);
         if (refNodeID == null) {
            refNodeID = referenceNodeStore.getDefaultNodeID();
         }

         if (refNodeID.equals(nodeID)) {
            long diff = refMessageID - messageID;
            if (diff == 0) {
               return 0;
            } else if (diff > 0) {
               return 1;
            } else {
               return -1;
            }
         } else {
            return -1;
         }
      }

      @Override
      public void run() {
         operation.done();
      }
   }
}
