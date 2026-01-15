package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ApplicationContext applicationContext;  // 注入应用上下文
    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<Long>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //消息队列的名称
    private static final String streamQueueName = "stream.orders";
    //阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        this.proxy = applicationContext.getBean(IVoucherOrderService.class);
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取redis的stream队列中的订单信息
                    //xreadgroup group g1 c1 COUNT 1 BLOCK 2000 Streams stream.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(streamQueueName, ReadOffset.lastConsumed())
                    );
                    //2.判断获取消息是否成功
                    if (list == null || list.isEmpty()) {
                        //继续获取
                        continue;
                    }
                    //3.解析消息中的数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.下单
                    handleVoucherOrder(voucherOrder);
                    //5.进行ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(streamQueueName, "g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //对pending list中的异常消息进行处理
                    handlePendingList();
                }

            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取redis的pending list中的订单信息
                    //xreadgroup group g1 c1 COUNT 1 BLOCK 2000 Streams stream.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(streamQueueName, ReadOffset.from("0"))
                    );
                    //2.pending list中没有异常数据，结束循环
                    if (list == null || list.isEmpty()) {
                        //结束循环
                        return;
                    }
                    //3.解析消息中的数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.下单
                    handleVoucherOrder(voucherOrder);
                    //5.进行ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(streamQueueName, "g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending list中的异常", e);
                    //对pending list中的异常消息进行处理
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean success = lock.tryLock();
        if (!success) {
            log.error("不能重复下单");
            return;
        }
        try {
            proxy.createSeckillOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }



    @Override
    public Result seckillVoucher(Long voucherId) {
        //local stockKey = "seckill:stock:" .. voucherId
        //-- 2.2订单key
        //local orderKey = "seckill:order:" .. voucherId
        //生成订单id
        Long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),orderId.toString());
        int res = result.intValue();
        if (res != 0) {
            return res == 1 ? Result.fail("库存不足") : Result.fail("不能重复下单");
        }
        //获取代理对象

        //返回订单id
        return Result.ok(orderId);

    }

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //local stockKey = "seckill:stock:" .. voucherId
        //-- 2.2订单key
        //local orderKey = "seckill:order:" .. voucherId
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        int res = result.intValue();
        if (res != 0) {
            return res == 1 ? Result.fail("库存不足") : Result.fail("不能重复下单");
        }
        //生成订单id
        Long orderId = redisIdWorker.nextId("order");
        //将信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);

    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        Integer stock = voucher.getStock();
        if(stock <= 0){
            return Result.fail("库存不足");
        }
        Long id = UserHolder.getUser().getId();
//        Ilock lock = new SimpleRedisLock("order" +  id, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order" + id);
        boolean success = lock.tryLock();
        if(!success){
            return Result.fail("获取锁失败 同一用户不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createSeckillOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }*/
    @Transactional
    @Override
    public void createSeckillOrder(VoucherOrder voucherOrder) {
        //判断是否是重复秒杀
        Long userId = voucherOrder.getUserId();
        System.out.println("用户id：" + userId);
        int count = this.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //订单重复
        if (count > 0) {
            log.error("不能重复秒杀");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
        }
        save(voucherOrder);
    }
}
