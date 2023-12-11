package com.funcsales.controller;

import com.funcsales.dto.TradeDto;
import com.funcsales.service.TradeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trade")
@Api(tags = "交易接口")
public class TradeController {
    @Autowired
    private TradeService tradeService;

    @ApiOperation("申购接口")
    @PostMapping( "/purchase")
    public ResponseEntity<?> PurchaseTrade(@RequestBody TradeDto tradeDto){
               return tradeService.purchaseTrade(tradeDto);
    }

    @ApiOperation("赎回接口")
    @PostMapping("/redeem")
    public ResponseEntity<?> RedeemTrade(@RequestBody TradeDto tradeDto){
        return tradeService.redeemTrade(tradeDto);
    }
}