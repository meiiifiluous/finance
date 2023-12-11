package com.hdu.bankcard.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hdu.finance.client.CustomerClient;
import com.hdu.finance.common.domain.dto.BankCardDto;
import com.hdu.finance.common.domain.po.Bank;
import com.hdu.finance.common.domain.po.BankCard;
import com.hdu.finance.common.domain.vo.BankCardVo;
import com.hdu.bankcard.service.BankCardService;
import com.hdu.bankcard.service.BankService;
import com.hdu.finance.common.domain.common.Result;
import com.hdu.finance.common.domain.common.ResultCodeEnum;
import com.hdu.finance.common.domain.po.Customer;
import com.hdu.finance.common.utils.BeanUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author vvc
 * @since 2023-11-01
 */
@RestController
@RequestMapping("/bank/card")
@Api(tags = "银行卡绑定管理")
public class BankCardController {
    @Resource
    private BankCardService bankCardService;
    @Resource
    private BankService bankService;
    @Resource
    private CustomerClient customerClient;

    @ApiOperation("绑定银行卡")
    @PostMapping("/bind")
    public Result addBankAccount(@RequestBody BankCardDto bankCardDto) {
        if (StrUtil.isEmpty(bankCardDto.getBankCardNo())) {
            return Result.build(null, 211, "银行卡号为空！");
        }
        Pattern compile = Pattern.compile("^([1-9]{1})(\\d{15}|\\d{18})$");
        Matcher matcher = compile.matcher(bankCardDto.getBankCardNo());
        if (!matcher.matches()) {
            return Result.build(null, 212, "银行卡号格式错误!");
        }
        //获取银行信息
        String bankName = bankCardDto.getBankName();
        if (StrUtil.isEmpty(bankName)) {
            return Result.build(null, 213, "银行名称为空！");
        }
        LambdaQueryWrapper<Bank> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Bank::getBankName, bankName);
        Bank bank = bankService.getOne(queryWrapper);
        BankCard bankCard = new BankCard();
        bankCard.setBankId(bank.getId());
        bankCard.setBankCode(bank.getBankCode());
        bankCard.setStatus(1);
        bankCard.setBankType(bankCardDto.getBankType());

        //查找客户号
        String customerId = customerClient.getByName(bankCardDto.getCustomerName()).getCustomerId();
        bankCard.setCustomerId(customerId);
        //随机生成余额
        Random random = new Random();
        int number = random.nextInt(10000 - 1000 + 1) + 1000;
        BigDecimal balance = new BigDecimal(number);
        bankCard.setAccountBalance(balance);
        bankCard.setBankCardNo(bankCardDto.getBankCardNo());
        bankCardService.save(bankCard);
        return Result.build(null, ResultCodeEnum.SUCCESS);
    }

    @ApiOperation("解绑")
    @DeleteMapping("/delete/{id}")
    public Result unbindBankAccount(@PathVariable Long id) {
        bankCardService.removeById(id);
        return Result.build(null, ResultCodeEnum.SUCCESS);
    }

    @ApiOperation("根据客户姓名查询所持有的银行卡")
    @GetMapping("/get")
    public Result getBankCards(String realName) {
        LambdaQueryWrapper<Customer> customerLambdaQueryWrapper = new LambdaQueryWrapper<>();
        String customerId = customerClient.getByName(realName).getCustomerId();
        List<BankCardVo> list = bankCardService.lambdaQuery()
                .eq(BankCard::getCustomerId, customerId)
                .eq(BankCard::getStatus, "1")
                .list().stream().map(bankCard -> {
                    BankCardVo bankCardVo = BeanUtils.copyBean(bankCard, BankCardVo.class);
                    LambdaQueryWrapper<Bank> bankLambdaQueryWrapper = new LambdaQueryWrapper<>();
                    bankLambdaQueryWrapper.eq(Bank::getId, bankCard.getBankId());
                    bankCardVo.setBankName(bankService.getOne(bankLambdaQueryWrapper).getBankName());
                    return bankCardVo;
                })
                .collect(Collectors.toList());
        return Result.build(list, ResultCodeEnum.SUCCESS);
    }

    @ApiOperation("冻结银行卡")
    @PutMapping("/freezeCard/{id}")
    public Result FreezeCard(@PathVariable Long id) {
        BankCard bankCard = bankCardService.getById(id);
        bankCard.setStatus(0);
        bankCardService.updateById(bankCard);
        return Result.build(null, ResultCodeEnum.SUCCESS);
    }
}
