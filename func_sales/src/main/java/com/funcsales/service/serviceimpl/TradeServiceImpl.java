package com.funcsales.service.serviceimpl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.funcsales.dto.PageDto;
import com.funcsales.dto.TradeDto;
import com.funcsales.mapper.*;
import com.funcsales.po.*;
import com.funcsales.query.TradeQuery;
import com.funcsales.service.HoldcapitalService;
import com.funcsales.service.TradeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.funcsales.vo.TradeVo;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static java.sql.Types.NULL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2023-11-07
 */

@Service
public class TradeServiceImpl extends ServiceImpl<TradeMapper, Trade> implements TradeService {
    @Autowired
    private BankMapper bankMapper;
    @Autowired
    private BankCardMapper bankCardMapper;
    @Autowired
    private FundMapper fundMapper;
    @Autowired
    private TradeService tradeService;
    @Autowired
    private FundnavMapper fundnavMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private HoldcapitalMapper holdcapitalMapper;
    @Autowired
    private HoldcapitalService holdcapitalService;
    @Autowired
    private TradeMapper tradeMapper;

    @Override
    public ResponseEntity<?> purchaseTrade(TradeDto tradeDto) {
        //检查用户状态
        String idCard=tradeDto.getIdentityNumber();
        Customer customer=customerMapper.getCustomerByIdCard(idCard);
        if(customer.getStatus()==0){
            throw new RuntimeException("该用户状态异常");
        }
        //检查银行卡状态
        String bankCardNo=tradeDto.getBankCardNo();
        BankCard bankCard=bankCardMapper.getBankCardById(bankCardNo);
        if(bankCard.getStatus()==0){
            throw new RuntimeException("该银行卡已被冻结，无法进行交易");
        }
        //检查基金是否在销售
        String productCode=tradeDto.getProductCode();
        Fundnav fundnav=fundnavMapper.getFundnavById(productCode);
        if(fundnav.getOkSale()==0){
            throw new RuntimeException("该基金未在销售");
        }

        //检查基金是否可申购
        Fund fund=fundMapper.getFundById(productCode);
        if(fund.getIsApply()){
            throw new RuntimeException("该基金不可申购");
        }

        //判断是否达到最低申购金额
        BigDecimal tradeAmount= tradeDto.getTradeAmount();
        if (tradeAmount.compareTo(fund.getMinApplyAmount())<0){
            throw new RuntimeException("未达到最低申购金额");
        }

        Trade trade = new Trade();
        //检查客户风险等级和产品风险等级是否匹配
        //获取客户的风险等级
        Integer investmentType= customer.getInvestmentType();
        if(investmentType== NULL){
            throw new RuntimeException("该客户未进行风险等级评估或风险等级评估已过期，请重新进行评估");
        }
        //获取基金类型
        Integer riskLevel= fund.getRiskLevel();
        if(riskLevel>investmentType){
            String message = "基金风险等级与您的风险等级不匹配，是否确认购买？";
            return new ResponseEntity<>(message, HttpStatus.CONFLICT);
        }else{
            // 检查客户银行卡余额是否足够扣款
            BigDecimal differenc=bankCard.getAccountBalance().subtract(tradeDto.getTradeAmount()).subtract(tradeDto.getFee());
            if(differenc.compareTo(BigDecimal.ZERO)<0){
                throw new RuntimeException("余额不足");
            }
            //更新交易表
            //把tradeDto拷贝到trade
            trade = BeanUtil.copyProperties(tradeDto, Trade.class);
            //修改剩余字段
            UUID uuid=  UUID.randomUUID();
            String flowNumber = uuid.toString();
            trade.setTradeNo(flowNumber);
            trade.setConfirmStatus(1);
            trade.setCreateTimec(LocalDateTime.now());
            LocalDateTime updateTime=calculateConfirmTime(tradeDto.getTradeTime());
            trade.setUpdateTime(updateTime);
            trade.setCustId(customer.getCustomerId());
            String bankCode=bankCard.getBankCode();
            Bank bank=bankMapper.getBankById(bankCode);
            trade.setBankName(bank.getBankName());
            trade.setProductName(fund.getChiName());
            trade.setPayStatus(String.valueOf(1));
            trade.setProductPrice(fundnav.getNetvalue());
            tradeService.save(trade);
            //银行卡进行扣款
            bankCardMapper.updateAccountBalance(differenc,bankCardNo);

            //更新客户持有资产表
            // 分解 Lambda 查询条件
            LambdaQueryWrapper<Holdcapital> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Holdcapital::getCustomerId, trade.getCustId())
                    .eq(Holdcapital::getBankCardNo, trade.getBankCardNo())
                    .eq(Holdcapital::getProductCode, trade.getProductCode());
            // 使用 selectOne 方法重载
            Holdcapital holdcapital = holdcapitalMapper.selectOne(queryWrapper);
            if(holdcapital!=null){
                //申赎资产包括已经提交给基金公司的未处理的申购或赎回订单的总价值
                holdcapitalMapper.UpdateTradeAmount(trade.getTradeAmount().add(holdcapital.getTradeAmount()),trade.getCustId(),trade.getProductCode(),trade.getBankCardNo());
                holdcapitalMapper.UpdateUtime(trade.getCustId(),trade.getProductCode(),trade.getBankCardNo());
            }else{
                Holdcapital holdcapital1=new Holdcapital();
                holdcapital1.setCreateTime(LocalDateTime.now());
                holdcapital1.setCustomerId(trade.getCustId());
                holdcapital1.setHoldCapital(BigDecimal.valueOf(0));
                holdcapital1.setProductCode(trade.getProductCode());
                String productName=fund.getChiName();
                holdcapital1.setProductName(productName);
                holdcapital1.setStatus(String.valueOf(1));
                holdcapital1.setTotalProfit(BigDecimal.valueOf(0));
                holdcapital1.setTradeAmount(trade.getTradeAmount());
                holdcapital1.setUpdateTime(LocalDateTime.now());
                holdcapital1.setYesterdayProfit(BigDecimal.valueOf(0));
                String fundType=fund.getFundType();
                holdcapital1.setProductType(fundType);
                holdcapital1.setIsDeleted(0);
                holdcapital1.setBankCardNo(trade.getBankCardNo());
                holdcapitalService.save(holdcapital1);
            }
        }
        //把PO拷贝到VO
        TradeVo tradeVo=BeanUtil.copyProperties(trade, TradeVo.class);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(tradeVo);
    }

    @Override
    public ResponseEntity<?> redeemTrade(TradeDto tradeDto) {
        //检查用户状态
        String idCard=tradeDto.getIdentityNumber();
        Customer customer=customerMapper.getCustomerByIdCard(idCard);
        String custId=customer.getCustomerId();
        if(customer.getStatus()==0){
            throw new RuntimeException("该用户状态异常");
        }
        //检查银行卡状态
        String bankCardNo=tradeDto.getBankCardNo();
        BankCard bankCard=bankCardMapper.getBankCardById(bankCardNo);
        if(bankCard.getStatus()==0){
            throw new RuntimeException("该银行卡已被冻结，无法进行交易");
        }
        //检查基金是否在销售
        String productCode=tradeDto.getProductCode();
        Fundnav fundnav=fundnavMapper.getFundnavById(productCode);
        if(fundnav.getOkSale()==0){
            throw new RuntimeException("该基金未在销售");
        }
        //检查基金是否可赎回
        Fund fund=fundMapper.getFundById(productCode);
        if(fund.getIsRedemption()){
            throw new RuntimeException("该基金不可赎回");
        }
        //判断是否达到最低赎回金额
        BigDecimal tradeAmount= tradeDto.getTradeAmount();
        if(tradeAmount.compareTo(fund.getLowestRedemption())<0){
            throw new RuntimeException("未达到最低赎回金额");
        }

        Trade trade = new Trade();

        //从客户资产持有表中判断该客户该银行卡是否购买过该基金
        LambdaQueryWrapper<Holdcapital> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Holdcapital::getCustomerId, custId)
                .eq(Holdcapital::getBankCardNo, tradeDto.getBankCardNo())
                .eq(Holdcapital::getProductCode, tradeDto.getProductCode());
        // 使用 selectOne 方法重载
        Holdcapital holdcapital = holdcapitalMapper.selectOne(queryWrapper);

        if(holdcapital==null){
            throw new RuntimeException("该银行卡未购买过该基金，不能进行赎回");
        }
        if(holdcapital.getHoldCapital().compareTo(tradeAmount)<0){
            throw new RuntimeException("该基金可赎回金额不足，不能进行赎回");
        }

        //更新交易表
        //把tradeDto拷贝到trade
        trade = BeanUtil.copyProperties(tradeDto, Trade.class);
        //修改剩余字段
        UUID uuid=  UUID.randomUUID();
        String flowNumber = uuid.toString();
        trade.setTradeNo(flowNumber);
        trade.setConfirmStatus(1);
        trade.setCreateTimec(LocalDateTime.now());
        LocalDateTime updateTime=calculateConfirmTime(tradeDto.getTradeTime());
        trade.setUpdateTime(updateTime);
        String bankCode=bankCard.getBankCode();
        Bank bank=bankMapper.getBankById(bankCode);
        trade.setBankName(bank.getBankName());
        trade.setProductName(fund.getChiName());
        trade.setProductPrice(fundnav.getNetvalue());
        tradeService.save(trade);

        //更新银行卡余额
        bankCardMapper.updateAccountBalance(bankCard.getAccountBalance().add(tradeAmount),bankCardNo);

        //更新客户持有资产表
        holdcapitalMapper.UpdateTradeAmount(trade.getTradeAmount().add(holdcapital.getTradeAmount()),trade.getCustId(),trade.getProductCode(),trade.getBankCardNo());
        holdcapitalMapper.UpdateUtime(trade.getCustId(),trade.getProductCode(),trade.getBankCardNo());

        //把PO拷贝到VO
        TradeVo tradeVo=BeanUtil.copyProperties(trade, TradeVo.class);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(tradeVo);
    }

    @Override
    public PageDto<TradeVo> queryTrade(TradeQuery query) {
        //构建分页条件
        Page<Trade> page=Page.of(query.getPageNo(),query.getPageSize());
        //排序条件
        if(StrUtil.isNotBlank(query.getSortBy())){
            //不为空
            page.addOrder(new OrderItem(query.getSortBy(),query.getIsAsc()));
        }else{
            //为空，按照创建时间排序
            page.addOrder(new OrderItem("create_timec", query.getIsAsc()));
        }

        //分页查询
        //根据身份证号查找客户号
        String custId=customerMapper.getCustIdByIdCard(query.getIdentityNumber());
        Page<Trade> page1=lambdaQuery()
                .eq(custId!=null,Trade::getCustId,custId)
                .eq(query.getBankCardNo()!=null,Trade::getBankCardNo,query.getBankCardNo())
                .eq(query.getFundCode()!=null,Trade::getProductCode,query.getFundCode())
                .like(query.getProductName()!=null,Trade::getProductName,query.getProductName())
                .eq(query.getConfirmStatus()!=null,Trade::getConfirmStatus,query.getConfirmStatus())
                .eq(Trade::getIsDeleted,0)
                .page(page);
        //封装VO结果
        PageDto<TradeVo> dto=new PageDto<>();
        dto.setTotal(page1.getTotal());
        dto.setPages(page1.getPages());
        List<Trade> records=page1.getRecords();
        if(CollUtil.isEmpty(records)){
            dto.setList(Collections.emptyList());
            return dto;
        }
        List<TradeVo> vos=BeanUtil.copyToList(records, TradeVo.class);
        dto.setList(vos);
        //返回
        return dto;
    }

    @Override
    public boolean deleteTrade(Long tradeId, Integer confirmStatus) {
        return tradeMapper.DeleteTrade(tradeId,confirmStatus);
    }

    //计算预计确认时间
    //如果下单时间是今天三点前，则预计确认时间是第二天上午八点；
    // 如果是三点后，则预计确认时间是第三天上午八点，节假日不工作，周五下单的下周一上午八点确认,节日下单的下个工作日上午八点确认
    private LocalDateTime calculateConfirmTime(LocalDateTime tradeTime) {
        // 获取下单当天 15:00 的时间
        LocalDateTime today15 = LocalDateTime.of(tradeTime.toLocalDate(), LocalTime.of(15, 0));
        // 获取下单第二天和下单第三天的日期
        LocalDate tomorrow = tradeTime.toLocalDate().plusDays(1);
        LocalDate dayAfterTomorrow = tradeTime.toLocalDate().plusDays(2);

        // 检查下单时间与今天 15:00 的关系
        if (tradeTime.isBefore(today15)) {
            // 如果下单时间在今天 15:00 之前
            // 判断是否为周五，如果是，则确认时间为下周一
            if (tradeTime.toLocalDate().getDayOfWeek() == DayOfWeek.FRIDAY) {
                // 判断是否为节假日，如果是，则获取下一个工作日
                if (isHoliday(tradeTime.toLocalDate().plusDays(3))) {
                    return LocalDateTime.of(getNextWorkingDay(tradeTime.toLocalDate().plusDays(3)), LocalTime.of(8, 0));
                } else {
                    return LocalDateTime.of(tradeTime.toLocalDate().plusDays(3), LocalTime.of(8, 0));
                }
            } else {
                // 判断是否为节假日，如果是，则获取下一个工作日
                if (isHoliday(tomorrow)) {
                    return LocalDateTime.of(getNextWorkingDay(tomorrow), LocalTime.of(8, 0));
                } else {
                    return LocalDateTime.of(tomorrow, LocalTime.of(8, 0));
                }
            }
        } else {
            // 如果下单时间在今天 15:00 之后
            // 判断是否为周五，如果是，则确认时间为下周一
            if (LocalDate.now().getDayOfWeek() == DayOfWeek.FRIDAY) {
                // 判断是否为节假日，如果是，则获取下一个工作日
                if (isHoliday(LocalDate.now().plusDays(3))) {
                    return LocalDateTime.of(getNextWorkingDay(LocalDate.now().plusDays(3)), LocalTime.of(8, 0));
                } else {
                    return LocalDateTime.of(LocalDate.now().plusDays(4), LocalTime.of(8, 0));
                }
            } else {
                // 判断是否为节假日，如果是，则获取下一个工作日
                if (isHoliday(dayAfterTomorrow)) {
                    return LocalDateTime.of(getNextWorkingDay(dayAfterTomorrow), LocalTime.of(8, 0));
                } else {
                    return LocalDateTime.of(dayAfterTomorrow, LocalTime.of(8, 0));
                }
            }
        }
    }
    public boolean isHoliday(LocalDate date) {
        String url = "http://timor.tech/api/holiday/year";
        CloseableHttpClient client = HttpClients.createDefault();
        HttpGet request = new HttpGet(url);

        try {
            HttpResponse response = client.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String str = EntityUtils.toString(entity, "UTF-8");

                JSONObject obj = JSON.parseObject(str);
                if (obj.getInteger("code") == 0) {
                    JSONObject holiday = obj.getJSONObject("holiday");
                    String formattedDate = date.format(DateTimeFormatter.ofPattern("MM-dd"));
                    if (holiday.containsKey(formattedDate)) {
                        JSONObject holidayInfo = holiday.getJSONObject(formattedDate);
                        if (holidayInfo != null && holidayInfo.getBoolean("holiday")) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
    //判断工作日
    private LocalDate getNextWorkingDay(@NotNull LocalDate date) {
        LocalDate nextDay = date.plusDays(1);  // 先计算出下一天日期
        while (isHoliday(nextDay) || nextDay.getDayOfWeek() == DayOfWeek.SATURDAY || nextDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nextDay = nextDay.plusDays(1);  // 如果是节假日或周末，继续找下一天
        }
        return nextDay;
    }


}

