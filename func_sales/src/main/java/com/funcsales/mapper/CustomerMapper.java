package com.funcsales.mapper;

import com.funcsales.po.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 
 * @since 2023-11-10
 */
public interface CustomerMapper extends BaseMapper<Customer> {
    @Select("SELECT * FROM c_customer WHERE customer_id = #{custId} AND is_deleted = 0")
    Customer getCustomerById(@Param("custId") String custId);
    //根据身份证号查找客户号
    @Select("SELECT customer_id FROM c_customer WHERE identity_number = #{identityNumber} AND is_deleted = 0")
    String getCustIdByIdCard(@Param("identityNumber")String identitNnumber);
    //根据身份证号查找客户id
    @Select("SELECT id FROM c_customer WHERE identity_number = #{identityNumber} AND is_deleted = 0")
    Long getIdByIdCard(@Param("identityNumber")String identitNnumber);
    //根据身份证号查找客户信息
    @Select("SELECT * FROM c_customer WHERE identity_number = #{identityNumber}AND is_deleted = 0")
    Customer getCustomerByIdCard(@Param("identityNumber")String identitNnumber);


}
