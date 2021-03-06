package io.nebulas.explorer.grpc;

import com.alibaba.fastjson.JSONObject;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.nebulas.explorer.domain.NebAddress;
import io.nebulas.explorer.domain.NebBlock;
import io.nebulas.explorer.domain.NebPendingTransaction;
import io.nebulas.explorer.domain.NebTransaction;
import io.nebulas.explorer.model.Block;
import io.nebulas.explorer.model.DposContext;
import io.nebulas.explorer.model.Transaction;
import io.nebulas.explorer.service.blockchain.NebAddressService;
import io.nebulas.explorer.service.blockchain.NebBlockService;
import io.nebulas.explorer.service.blockchain.NebDynastyService;
import io.nebulas.explorer.service.blockchain.NebTransactionService;
import io.nebulas.explorer.service.thirdpart.nebulas.NebulasApiService;
import io.nebulas.explorer.service.thirdpart.nebulas.bean.*;
import io.nebulas.explorer.util.IdGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import rpcpb.ApiServiceGrpc;
import rpcpb.Rpc;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Title.
 * <p>
 * Description.
 *
 * @author Bill
 * @version 1.0
 * @since 2018-01-23
 */
@Slf4j(topic = "subscribe")
@AllArgsConstructor
@Service
public class GrpcClientService {
    private GrpcChannelService grpcChannelService;
    private NebBlockService nebBlockService;
    private NebTransactionService nebTransactionService;
    private NebAddressService nebAddressService;
    private NebDynastyService nebDynastyService;
    private NebulasApiService nebulasApiService;

    public void subscribe() {
        Channel channel = grpcChannelService.getChannel();
        ApiServiceGrpc.ApiServiceStub asyncStub = ApiServiceGrpc.newStub(channel);
        final CountDownLatch finishLatch = new CountDownLatch(1);
        StreamObserver<Rpc.SubscribeResponse> responseObserver = new StreamObserver<Rpc.SubscribeResponse>() {
            @Override
            public void onNext(Rpc.SubscribeResponse sr) {
                String dataStr = sr.getData();
                log.info("msg type: {}, data: {}", sr.getTopic(), dataStr);
                if (StringUtils.isBlank(dataStr)) {
                    log.error("empty data");
                    return;
                }
                JSONObject data;
                try {
                    data = JSONObject.parseObject(dataStr);
                } catch (Throwable e) {
                    log.error(String.format("data string %s can NOT parse into json", dataStr), e);
                    return;
                }

                String topic = sr.getTopic();
                String hash = data.getString("hash");
                if (Const.TopicLinkBlock.equals(topic)) {
                    processTopicLinkBlock(hash);
                } else if (Const.TopicPendingTransaction.equals(topic)) {
                    processTopicPendingTransaction(hash);
                } else if (Const.TopicLibBlock.equals(topic)) {
                    processTopicLibBlock(hash);
                }
            }

            @Override
            public void onError(Throwable t) {
                Status status = Status.fromThrowable(t);
                log.warn("failed: {}", status);
                try {
                    grpcChannelService.renewChannel();
                    // sleep for 10 seconds to enter next retry
                    log.info("entering next retry after 10 seconds ....");
                    TimeUnit.SECONDS.sleep(10);
                    subscribe();
                } catch (InterruptedException e1) {
                    log.error("thread sleep interrupted, skipped reconnect", e1);
                    finishLatch.countDown();
                }
            }

            @Override
            public void onCompleted() {
                log.info("Finished");
                finishLatch.countDown();
            }
        };

        asyncStub.subscribe(Rpc.SubscribeRequest.newBuilder()
                .addTopics(Const.TopicPendingTransaction)
                .addTopics(Const.TopicLinkBlock)
                .addTopics(Const.TopicLibBlock)
                .build(), responseObserver);
    }

    private void processTopicPendingTransaction(String hash) {
        if (StringUtils.isBlank(hash)) {
            log.error("empty hash");
            return;
        }

        NebPendingTransaction pendingNebTransaction = nebTransactionService.getNebPendingTransactionByHash(hash);
        if (pendingNebTransaction == null) {
            Transaction txSource;
            try {
                txSource = nebulasApiService.getTransactionReceipt(new GetTransactionReceiptRequest(hash)).execute().body();
            } catch (Exception e) {
                log.error("get tx by hash error, skipped pending tx hash " + hash, e);
                return;
            }
            if (txSource == null) {
                log.warn("pending tx with hash {} not ready", hash);
            } else {
                int type = StringUtils.isBlank(txSource.getContractAddress()) ? 0 : 1;
                addAddr(txSource.getFrom(), type);
                addAddr(txSource.getTo(), type);
                log.info("get pending tx by hash {}", hash);
                NebPendingTransaction pendingTxToSave = NebPendingTransaction.builder()
                        .id(IdGenerator.getId())
                        .hash(hash)
                        .from(txSource.getFrom())
                        .to(txSource.getTo())
                        .value(txSource.getValue())
                        .nonce(txSource.getNonce())
                        .timestamp(new Date(txSource.getTimestamp() * 1000))
                        .type(txSource.getType())
                        .gasPrice(txSource.getGasPrice())
                        .gasLimit(txSource.getGasLimit())
                        .createdAt(new Date())
                        .data(txSource.getData())
                        .build();
                nebTransactionService.addNebPendingTransaction(pendingTxToSave);
            }
        } else {
            log.warn("duplicate pending neb transaction {}", pendingNebTransaction.getHash());
        }
    }

    private void processTopicLinkBlock(String hash) {
        if (StringUtils.isBlank(hash)) {
            log.error("empty hash");
            return;
        }

        NebBlock nebBlock = nebBlockService.getNebBlockByHash(hash);
        if (null != nebBlock) {
            log.warn("block with hash {} already existed", hash);
            return;
        }
        try {
            Block block = nebulasApiService.getBlockByHash(new GetBlockByHashRequest(hash, true)).toBlocking().first();
            if (block == null) {
                log.warn("block with hash {} not ready", hash);
                return;
            }

            log.info("get block by height {}", block.getHeight());

            addAddr(block.getMiner(), 0);
            addAddr(block.getCoinbase(), 0);

            Block latestIrreversibleBlk = null;
            try {
                latestIrreversibleBlk = nebulasApiService.getLatestIrreversibleBlock().toBlocking().first();
            } catch (StatusRuntimeException e) {
                log.error("get block by height error", e);
            }

            NebBlock newBlock = NebBlock.builder()
                    .id(IdGenerator.getId())
                    .height(block.getHeight())
                    .hash(block.getHash())
                    .parentHash(block.getParentHash())
                    .timestamp(new Date(block.getTimestamp() * 1000))
                    .miner(block.getMiner())
                    .coinbase(block.getCoinbase())
                    .nonce(block.getNonce())
                    .finality(latestIrreversibleBlk != null && block.getHeight() <= latestIrreversibleBlk.getHeight())
                    .createdAt(new Date(System.currentTimeMillis())).build();
            nebBlockService.addNebBlock(newBlock);

            List<Transaction> txs = block.getTransactions();
            for (Transaction tx : txs) {
                int type = StringUtils.isBlank(tx.getContractAddress()) ? 0 : 1;
                addAddr(tx.getFrom(), type);
                addAddr(tx.getTo(), type);
                String gasUsed;
                try {
                    GetGasUsedResponse gasUsedResponse = nebulasApiService.getGasUsed(new GetGasUsedRequest(tx.getHash())).toBlocking().first();
                    gasUsed = gasUsedResponse.getGas();
                } catch (Exception e) {
                    log.error("get gas used error, set gas used to null for tx hash " + tx.getHash(), e);
                    gasUsed = null;
                }
                if (gasUsed != null) {
                    log.info("gas used: {}", gasUsed);
                }
                NebPendingTransaction nebPendingTransaction = nebTransactionService.getNebPendingTransactionByHash(tx.getHash());
                if (nebPendingTransaction != null) {
                    nebTransactionService.deleteNebPendingTransaction(nebPendingTransaction.getId());
                }
                NebTransaction nebTxs = NebTransaction.builder()
                        .id(IdGenerator.getId())
                        .hash(tx.getHash())
                        .blockHeight(block.getHeight())
                        .blockHash(block.getHash())
                        .from(tx.getFrom())
                        .to(tx.getTo())
                        .status(tx.getStatus())
                        .value(tx.getValue())
                        .nonce(tx.getNonce())
                        .timestamp(new Date(tx.getTimestamp() * 1000))
                        .type(tx.getType())
                        .data(tx.getData())
                        .gasPrice(tx.getGasPrice())
                        .gasLimit(tx.getGasLimit())
                        .gasUsed(gasUsed)
                        .createdAt(new Date())
                        .build();
                nebTransactionService.addNebTransaction(nebTxs);
            }

            GetDynastyResponse dynastyResponse = nebulasApiService.getDynasty(new GetDynastyRequest(block.getHeight())).toBlocking().first();
            nebDynastyService.batchAddNebDynasty(block.getHeight(), dynastyResponse.getDelegatees());

        } catch (Exception e) {
            log.error("no block yet", e);
        } catch (Throwable e) {
            log.error("sys error", e);
        }
    }

    private void processTopicLibBlock(String hash) {
        NebBlock block = nebBlockService.getNebBlockByHash(hash);
        if (null != block) {
            nebBlockService.updateBlockIrreversible(block.getHeight());
        }
        log.warn("block with hash {} has not been synced", hash);
    }

    private void addAddr(String hash, int type) {
        NebAddress addr = nebAddressService.getNebAddressByHash(hash);
        if (addr == null) {
            try {
                nebAddressService.addNebAddress(hash, type);
            } catch (Throwable e) {
                log.error("add address error", e);
            }
        }
    }

    public Block getBlockByHash(String hash, Boolean fullTransaction) throws UnsupportedEncodingException {
        ApiServiceGrpc.ApiServiceBlockingStub stub = ApiServiceGrpc.newBlockingStub(grpcChannelService.getChannel());
        Rpc.BlockResponse block;
        try {
            block = stub.getBlockByHash(Rpc.GetBlockByHashRequest.newBuilder().setHash(hash).setFullTransaction(fullTransaction).build());
        } catch (StatusRuntimeException e) {
            String errMessage = e.getMessage();
            if (errMessage.contains("not found") || errMessage.contains("invalid byte")) {
                return null;
            }
            throw e;
        }
        return populateBlock(block);
    }

    public Block getBlockByHeight(long height, boolean fullTransaction) throws UnsupportedEncodingException {
        ApiServiceGrpc.ApiServiceBlockingStub stub = ApiServiceGrpc.newBlockingStub(grpcChannelService.getChannel());
        Rpc.BlockResponse response;
        try {
            response = stub.getBlockByHeight(Rpc.GetBlockByHeightRequest.newBuilder()
                    .setHeight(height)
                    .setFullTransaction(fullTransaction)
                    .build());
        } catch (StatusRuntimeException e) {
            String errMessage = e.getMessage();
            if (errMessage.contains("not found") || errMessage.contains("invalid byte")) {
                return null;
            }
            throw e;
        }
        return populateBlock(response);
    }

    private Block populateBlock(Rpc.BlockResponse response) throws UnsupportedEncodingException {
        if (response == null) {
            return null;
        }
        Rpc.DposContext dposContext = response.getDposContext();
        DposContext dposCxt = populateDposContext(dposContext);
        List<Rpc.TransactionResponse> transactionsList = response.getTransactionsList();
        List<Transaction> transactions = populateTransactionList(transactionsList);

        return new Block(response.getHash(), response.getParentHash(), response.getHeight()
                , response.getNonce(), response.getCoinbase(), response.getMiner(), response.getTimestamp()
                , response.getChainId(), response.getStateRoot(), response.getTxsRoot(), response.getEventsRoot()
                , dposCxt, transactions);
    }

    private DposContext populateDposContext(Rpc.DposContext dposContext) {
        return dposContext == null ? null
                : new DposContext(dposContext.getDynastyRoot(), dposContext.getNextDynastyRoot()
                , dposContext.getDelegateRoot(), dposContext.getCandidateRoot(), dposContext.getVoteRoot()
                , dposContext.getMintCntRoot());
    }

    private List<Transaction> populateTransactionList(List<Rpc.TransactionResponse> transactionResponseList) throws UnsupportedEncodingException {
        if (transactionResponseList == null || transactionResponseList.isEmpty()) {
            return new ArrayList<>(0);
        }
        List<Transaction> transactions = new ArrayList<>(transactionResponseList.size());
        for (Rpc.TransactionResponse tr : transactionResponseList) {
            Transaction transaction = populateTransaction(tr);
            if (transaction != null) {
                transactions.add(transaction);
            }
        }
        return transactions;
    }

    private Transaction populateTransaction(Rpc.TransactionResponse transactionResponse) throws UnsupportedEncodingException {
        return transactionResponse == null ? null
                : new Transaction(transactionResponse.getHash(), transactionResponse.getChainId()
                , transactionResponse.getFrom(), transactionResponse.getTo(), transactionResponse.getValue()
                , transactionResponse.getNonce(), transactionResponse.getTimestamp(), transactionResponse.getType()
                , transactionResponse.getData() == null ? null : transactionResponse.getData().toString("UTF-8")
                , transactionResponse.getGasPrice(), transactionResponse.getGasUsed(), transactionResponse.getGasLimit(), transactionResponse.getContractAddress()
                , transactionResponse.getStatus());
    }

}
