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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.IInvoice;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.VatLine;
import com.axelor.apps.account.service.invoice.generator.InvoiceGenerator;
import com.axelor.apps.account.service.invoice.generator.InvoiceLineGenerator;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductVariant;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.organisation.db.Task;
import com.axelor.apps.supplychain.db.SalesOrder;
import com.axelor.apps.supplychain.db.SalesOrderLine;
import com.axelor.apps.supplychain.db.SalesOrderSubLine;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class SalesOrderInvoiceService {

	private static final Logger LOG = LoggerFactory.getLogger(SalesOrderInvoiceService.class); 

	@Inject
	private SalesOrderLineService salesOrderLineService;
	
	@Inject
	private CurrencyService currencyService;
	
	@Inject
	private SalesOrderLineVatService salesOrderLineVatService;
	

	private LocalDate today;
	
	@Inject
	public SalesOrderInvoiceService() {

		this.today = GeneralService.getTodayDate();
		
	}
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public Invoice generateInvoice(SalesOrder salesOrder) throws AxelorException  {
		
		this.checkIfSalesOrderIsCompletelyInvoiced(salesOrder);
		
		Invoice invoice = this.createInvoice(salesOrder);
		
		this.assignInvoice(salesOrder, invoice);
		
		this.fillSalesOrder(salesOrder, invoice).save();
		
		return invoice;
	}
	
	
	public void checkIfSalesOrderIsCompletelyInvoiced(SalesOrder salesOrder) throws AxelorException  {
		
		BigDecimal total = BigDecimal.ZERO;
		
		for(Invoice invoice : salesOrder.getInvoiceSet())  {
			if(invoice.getStatus().getCode().equals("dis"))  {
				total = total.add(invoice.getInTaxTotal());
			}
		}
		
		if(total.compareTo(salesOrder.getInTaxTotal()) == 0)  {
			throw new AxelorException(String.format("Le devis %s est déjà complêtement facturé", salesOrder.getSalesOrderSeq()), IException.CONFIGURATION_ERROR);
		}
		
	}
	
	
	public SalesOrder fillSalesOrder(SalesOrder salesOrder, Invoice invoice)  {
		
		salesOrder.setOrderDate(this.today);
		
		// TODO Créer une séquence pour les commandes (Porter sur la facture ?)
//		salesOrder.setOrderNumber();
		
		return salesOrder;
		
	}
	
	
	public SalesOrder assignInvoice(SalesOrder salesOrder, Invoice invoice)  {
		
		if(salesOrder.getInvoiceSet() != null)  {
			salesOrder.getInvoiceSet().add(invoice);
		}
		else  {
			salesOrder.setInvoiceSet(new HashSet<Invoice>());
			salesOrder.getInvoiceSet().add(invoice);
		}
		
		return salesOrder;
	}
	
	
	public Invoice createInvoice(SalesOrder salesOrder) throws AxelorException  {
		
		InvoiceGenerator invoiceGenerator = this.createInvoiceGenerator(salesOrder);
		
		Invoice invoice = invoiceGenerator.generate();
		
		invoiceGenerator.populate(invoice, this.createInvoiceLines(invoice, salesOrder.getSalesOrderLineList(), salesOrder.getShowDetailsInInvoice()));
		return invoice;
		
	}
	
	

	public InvoiceGenerator createInvoiceGenerator(SalesOrder salesOrder) throws AxelorException  {
		
		if(salesOrder.getCurrency() == null)  {
			throw new AxelorException(String.format("Veuillez selectionner une devise pour le devis %s ", salesOrder.getSalesOrderSeq()), IException.CONFIGURATION_ERROR);
		}
		
		InvoiceGenerator invoiceGenerator = new InvoiceGenerator(IInvoice.CLIENT_SALE, salesOrder.getCompany(),salesOrder.getPaymentCondition(), 
				salesOrder.getPaymentMode(), salesOrder.getMainInvoicingAddress(), salesOrder.getClientPartner(), salesOrder.getContactPartner(), 
				salesOrder.getCurrency(), salesOrder.getAffairProject(), salesOrder.getPriceList()) {
			
			@Override
			public Invoice generate() throws AxelorException {
				
				return super.createInvoiceHeader();
			}
		};
		
		return invoiceGenerator;
		
	}
	
	
	
	// TODO ajouter tri sur les séquences
	public List<InvoiceLine> createInvoiceLines(Invoice invoice, List<SalesOrderLine> salesOrderLineList, boolean showDetailsInInvoice) throws AxelorException  {
		
		List<InvoiceLine> invoiceLineList = new ArrayList<InvoiceLine>();
		
		for(SalesOrderLine salesOrderLine : salesOrderLineList)  {
			
			if(showDetailsInInvoice == true && salesOrderLine.getSalesOrderSubLineList() != null && !salesOrderLine.getSalesOrderSubLineList().isEmpty())  {
				
				for(SalesOrderSubLine salesOrderSubLine : salesOrderLine.getSalesOrderSubLineList())  {
					
					invoiceLineList.addAll(this.createInvoiceLine(invoice, salesOrderSubLine));
					
				}
			
			}
			else  {
				
				invoiceLineList.addAll(this.createInvoiceLine(invoice, salesOrderLine));
				
			}
		}
		
		return invoiceLineList;
		
	}
	
	
	public List<InvoiceLine> createInvoiceLine(Invoice invoice, Product product, String productName, BigDecimal price, String description, BigDecimal qty,
			Unit unit, VatLine vatLine, Task task, ProductVariant productVariant, BigDecimal discountAmount, int discountTypeSelect, BigDecimal exTaxTotal) throws AxelorException  {
		
		InvoiceLineGenerator invoiceLineGenerator = new InvoiceLineGenerator(invoice, product, productName, price, description, qty, unit, vatLine, task, product.getInvoiceLineType(), 
				productVariant, discountAmount, discountTypeSelect, exTaxTotal, false)  {
			@Override
			public List<InvoiceLine> creates() throws AxelorException {
				
				InvoiceLine invoiceLine = this.createInvoiceLine();
				
				List<InvoiceLine> invoiceLines = new ArrayList<InvoiceLine>();
				invoiceLines.add(invoiceLine);
				
				return invoiceLines;
			}
		};
		
		return invoiceLineGenerator.creates();
	}
	
	
	public List<InvoiceLine> createInvoiceLine(Invoice invoice, SalesOrderLine salesOrderLine) throws AxelorException  {
		
		return this.createInvoiceLine(invoice, salesOrderLine.getProduct(), salesOrderLine.getProductName(), 
				salesOrderLine.getPrice(), salesOrderLine.getDescription(), salesOrderLine.getQty(), salesOrderLine.getUnit(), salesOrderLine.getVatLine(), 
				salesOrderLine.getTask(), salesOrderLine.getProductVariant(), salesOrderLine.getDiscountAmount(), salesOrderLine.getDiscountTypeSelect(), salesOrderLine.getExTaxTotal());
		
		
	}
	
	
	public List<InvoiceLine> createInvoiceLine(Invoice invoice, SalesOrderSubLine salesOrderSubLine) throws AxelorException  {
		
		return this.createInvoiceLine(invoice, salesOrderSubLine.getProduct(), salesOrderSubLine.getProductName(), 
				salesOrderSubLine.getPrice(), salesOrderSubLine.getDescription(), salesOrderSubLine.getQty(), salesOrderSubLine.getUnit(), 
				salesOrderSubLine.getVatLine(), salesOrderSubLine.getSalesOrderLine().getTask(), salesOrderSubLine.getProductVariant(), 
				salesOrderSubLine.getDiscountAmount(), salesOrderSubLine.getDiscountTypeSelect(), salesOrderSubLine.getExTaxTotal());
		
	}
	
}


