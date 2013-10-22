/**
 * Copyright (c) 2012-2013 Axelor. All Rights Reserved.
 *
 * The contents of this file are subject to the Common Public
 * Attribution License Version 1.0 (the “License”); you may not use
 * this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://license.axelor.com/.
 *
 * The License is based on the Mozilla Public License Version 1.1 but
 * Sections 14 and 15 have been added to cover use of software over a
 * computer network and provide for limited attribution for the
 * Original Developer. In addition, Exhibit A has been modified to be
 * consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an “AS IS”
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is part of "Axelor Business Suite", developed by
 * Axelor exclusively.
 *
 * The Original Developer is the Initial Developer. The Initial Developer of
 * the Original Code is Axelor.
 *
 * All portions of the code written by Axelor are
 * Copyright (c) 2012-2013 Axelor. All Rights Reserved.
 */
package com.axelor.apps.supplychain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.VatLine;
import com.axelor.apps.account.service.AccountManagementService;
import com.axelor.apps.base.db.PriceList;
import com.axelor.apps.base.db.PriceListLine;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.PriceListService;
import com.axelor.apps.supplychain.db.SalesOrder;
import com.axelor.apps.supplychain.db.SalesOrderLine;
import com.axelor.apps.supplychain.db.SalesOrderSubLine;
import com.axelor.exception.AxelorException;
import com.google.inject.Inject;

public class SalesOrderLineService {

	private static final Logger LOG = LoggerFactory.getLogger(SalesOrderLineService.class); 
	
	@Inject
	private CurrencyService currencyService;
	
	@Inject
	private AccountManagementService accountManagementService;
	
	@Inject
	private PriceListService priceListService;
	
	@Inject
	private SalesOrderSubLineService salesOrderSubLineService;
	
	
	/**
	 * Calculer le montant HT d'une ligne de devis.
	 * 
	 * @param quantity
	 *          Quantité.
	 * @param price
	 *          Le prix.
	 * 
	 * @return 
	 * 			Le montant HT de la ligne.
	 */
	public static BigDecimal computeAmount(BigDecimal quantity, BigDecimal price) {

		BigDecimal amount = quantity.multiply(price).setScale(2, RoundingMode.HALF_EVEN);

		LOG.debug("Calcul du montant HT avec une quantité de {} pour {} : {}", new Object[] { quantity, price, amount });

		return amount;
	}
	
	
	public BigDecimal getUnitPrice(SalesOrder salesOrder, SalesOrderLine salesOrderLine) throws AxelorException  {
		
		Product product = salesOrderLine.getProduct();
		
		return currencyService.getAmountCurrencyConverted(
			product.getSaleCurrency(), salesOrder.getCurrency(), product.getSalePrice(), salesOrder.getCreationDate());  
		
	}
	
	
	public VatLine getVatLine(SalesOrder salesOrder, SalesOrderLine salesOrderLine) throws AxelorException  {
		
		return accountManagementService.getVatLine(
				salesOrder.getCreationDate(), salesOrderLine.getProduct(), salesOrder.getCompany(), false);
		
	}
	
	
	public BigDecimal computeSalesOrderLine(SalesOrderLine salesOrderLine) throws AxelorException  {
		
		BigDecimal exTaxTotal = BigDecimal.ZERO;
		
		if(salesOrderLine.getSalesOrderSubLineList() != null && !salesOrderLine.getSalesOrderSubLineList().isEmpty())  {
			for(SalesOrderSubLine salesOrderSubLine : salesOrderLine.getSalesOrderSubLineList())  {
				
				salesOrderSubLine.setCompanyExTaxTotal(salesOrderSubLineService.getCompanyExTaxTotal(salesOrderLine.getExTaxTotal(), salesOrderLine.getSalesOrder()));
				
				exTaxTotal = exTaxTotal.add(salesOrderSubLine.getExTaxTotal());
			}
		}
		else  {
			return salesOrderLine.getExTaxTotal();
		}
		
		return exTaxTotal;
	}

	
	public BigDecimal getCompanyExTaxTotal(BigDecimal exTaxTotal, SalesOrder salesOrder) throws AxelorException  {
		
		return currencyService.getAmountCurrencyConverted(
				salesOrder.getCurrency(), salesOrder.getCompany().getCurrency(), exTaxTotal, salesOrder.getCreationDate());  
	}
	
	
	public BigDecimal getCompanyCostPrice(SalesOrder salesOrder, SalesOrderLine salesOrderLine) throws AxelorException  {
		
		Product product = salesOrderLine.getProduct();
		
		return currencyService.getAmountCurrencyConverted(
				product.getPurchaseCurrency(), salesOrder.getCompany().getCurrency(), product.getCostPrice(), salesOrder.getCreationDate());  
	}
	
	
	public PriceListLine getPriceListLine(SalesOrderLine salesOrderLine, PriceList priceList)  {
		
		return priceListService.getPriceListLine(salesOrderLine.getProduct(), salesOrderLine.getQty(), priceList);
	
	}
	
	
	public BigDecimal computeDiscount(SalesOrderLine salesOrderLine)  {
		
		return priceListService.computeDiscount(salesOrderLine.getPrice(), salesOrderLine.getDiscountTypeSelect(), salesOrderLine.getDiscountAmount());
		
	}
}
