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

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.IAdministration;
import com.axelor.apps.base.db.IProduct;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PriceList;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.UserInfo;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.organisation.db.Project;
import com.axelor.apps.supplychain.db.ILocation;
import com.axelor.apps.supplychain.db.IPurchaseOrder;
import com.axelor.apps.supplychain.db.Location;
import com.axelor.apps.supplychain.db.PurchaseOrder;
import com.axelor.apps.supplychain.db.PurchaseOrderLine;
import com.axelor.apps.supplychain.db.PurchaseOrderLineVat;
import com.axelor.apps.supplychain.db.StockMove;
import com.axelor.apps.supplychain.db.StockMoveLine;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class PurchaseOrderService {

	private static final Logger LOG = LoggerFactory.getLogger(PurchaseOrderService.class); 

	@Inject
	private PurchaseOrderLineService purchaseOrderLineService;
	
	@Inject
	private CurrencyService currencyService;
	
	@Inject
	private PurchaseOrderLineVatService purchaseOrderLineVatService;
	
	@Inject
	private StockMoveService stockMoveService;
	
	@Inject
	private StockMoveLineService stockMoveLineService;
	
	@Inject
	private SequenceService sequenceService;
	
	public PurchaseOrder _computePurchaseOrderLines(PurchaseOrder purchaseOrder) throws AxelorException  {
		
		if(purchaseOrder.getPurchaseOrderLineList() != null)  {
			for(PurchaseOrderLine purchaseOrderLine : purchaseOrder.getPurchaseOrderLineList())  {
				purchaseOrderLine.setExTaxTotal(purchaseOrderLineService.computePurchaseOrderLine(purchaseOrderLine));
				purchaseOrderLine.setCompanyExTaxTotal(purchaseOrderLineService.getCompanyExTaxTotal(purchaseOrderLine.getExTaxTotal(), purchaseOrder));
			}
		}
		
		return purchaseOrder;
	}
	

	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void computePurchaseOrder(PurchaseOrder purchaseOrder) throws AxelorException  {
		
		this.initPurchaseOrderLineVats(purchaseOrder);
		
		this._computePurchaseOrderLines(purchaseOrder);
		
		this._populatePurchaseOrder(purchaseOrder);
		
		this._computePurchaseOrder(purchaseOrder);
		
		purchaseOrder.save();
	}
	
	/**
	 * Peupler une commande.
	 * <p>
	 * Cette fonction permet de déterminer les tva d'une commande à partir des lignes de factures passées en paramètres. 
	 * </p>
	 * 
	 * @param purchaseOrder
	 * 
	 * @throws AxelorException
	 */
	public void _populatePurchaseOrder(PurchaseOrder purchaseOrder) throws AxelorException {
		
		LOG.debug("Peupler une facture => lignes de devis: {} ", new Object[] { purchaseOrder.getPurchaseOrderLineList().size() });
		
		// create Tva lines
		purchaseOrder.getPurchaseOrderLineVatList().addAll(purchaseOrderLineVatService.createsPurchaseOrderLineVat(purchaseOrder, purchaseOrder.getPurchaseOrderLineList()));
		
	}
	
	/**
	 * Calculer le montant d'une commande.
	 * <p> 
	 * Le calcul est basé sur les lignes de TVA préalablement créées.
	 * </p>
	 * 
	 * @param purchaseOrder
	 * @throws AxelorException 
	 */
	public void _computePurchaseOrder(PurchaseOrder purchaseOrder) throws AxelorException {
		
		purchaseOrder.setExTaxTotal(BigDecimal.ZERO);
		purchaseOrder.setVatTotal(BigDecimal.ZERO);
		purchaseOrder.setInTaxTotal(BigDecimal.ZERO);
		
		for (PurchaseOrderLineVat purchaseOrderLineVat : purchaseOrder.getPurchaseOrderLineVatList()) {
			
			// Dans la devise de la comptabilité du tiers
			purchaseOrder.setExTaxTotal(purchaseOrder.getExTaxTotal().add( purchaseOrderLineVat.getExTaxBase() ));
			purchaseOrder.setVatTotal(purchaseOrder.getVatTotal().add( purchaseOrderLineVat.getVatTotal() ));
			purchaseOrder.setInTaxTotal(purchaseOrder.getInTaxTotal().add( purchaseOrderLineVat.getInTaxTotal() ));
			
		}
		
		purchaseOrder.setAmountRemainingToBeInvoiced(purchaseOrder.getInTaxTotal());
		
		LOG.debug("Montant de la facture: HTT = {},  HT = {}, TVA = {}, TTC = {}",
			new Object[] { purchaseOrder.getExTaxTotal(), purchaseOrder.getVatTotal(), purchaseOrder.getInTaxTotal() });
		
	}

	
	/**
	 * Permet de réinitialiser la liste des lignes de TVA
	 * @param purchaseOrder
	 * 			Une commande.
	 */
	public void initPurchaseOrderLineVats(PurchaseOrder purchaseOrder) {
		
		if (purchaseOrder.getPurchaseOrderLineVatList() == null) { purchaseOrder.setPurchaseOrderLineVatList(new ArrayList<PurchaseOrderLineVat>()); }
		
		else { purchaseOrder.getPurchaseOrderLineVatList().clear(); }
		
	}
	
	
	
	public PurchaseOrder createPurchaseOrder(Project affairProject, UserInfo buyerUserInfo, Company company, Partner contactPartner, Currency currency, 
			LocalDate deliveryDate, String externalReference, int invoicingTypeSelect, Location location, LocalDate orderDate, PriceList priceList, Partner supplierPartner) throws AxelorException  {
		PurchaseOrder purchaseOrder = new PurchaseOrder();
		purchaseOrder.setAffairProject(affairProject);
		purchaseOrder.setBuyerUserInfo(buyerUserInfo);
		purchaseOrder.setCompany(company);
		purchaseOrder.setContactPartner(contactPartner);
		purchaseOrder.setCurrency(currency);
		purchaseOrder.setDeliveryDate(deliveryDate);
		purchaseOrder.setExternalReference(externalReference);
		purchaseOrder.setInvoicingTypeSelect(invoicingTypeSelect);
		purchaseOrder.setLocation(location);
		purchaseOrder.setOrderDate(orderDate);
		purchaseOrder.setPriceList(priceList);
		purchaseOrder.setPurchaseOrderLineList(new ArrayList<PurchaseOrderLine>());
		
		purchaseOrder.setPurchaseOrderSeq(this.getSequence(company));
		purchaseOrder.setStatusSelect(IPurchaseOrder.STATUS_DRAFT);
		purchaseOrder.setSupplierPartner(supplierPartner);
		
		return purchaseOrder;
	}
	
	
	public String getSequence(Company company) throws AxelorException  {
		String seq = sequenceService.getSequence(IAdministration.PURCHASE_ORDER,company,false);
		if (seq == null)  {
			throw new AxelorException(String.format("La société %s n'a pas de séquence de configurée pour les commandes fournisseur",company.getName()),
							IException.CONFIGURATION_ERROR);
		}
		return seq;
	}
	
	
	/**
	 * Méthode permettant de créer un StockMove à partir d'un PurchaseOrder.
	 * @param purchaseOrder une commande
	 * @throws AxelorException Aucune séquence de StockMove n'a été configurée
	 */
	public void createStocksMoves(PurchaseOrder purchaseOrder) throws AxelorException {
		
		if(purchaseOrder.getPurchaseOrderLineList() != null && purchaseOrder.getCompany() != null) {

			Company company = purchaseOrder.getCompany();
			
			Location startLocation = Location.all().filter("self.partner = ?1", purchaseOrder.getSupplierPartner()).fetchOne();
			
			if(startLocation == null)  {
				startLocation = company.getSupplierVirtualLocation();
			}
			if(startLocation == null)  {
				throw new AxelorException(String.format("%s Veuillez configurer un entrepot virtuel fournisseur pour la société %s ",
						GeneralService.getExceptionAccountingMsg(), company.getName()), IException.CONFIGURATION_ERROR);
			}

			StockMove stockMove = stockMoveService.createStockMove(null, company, purchaseOrder.getSupplierPartner(), startLocation, purchaseOrder.getLocation(), purchaseOrder.getDeliveryDate());
			stockMove.setPurchaseOrder(purchaseOrder);
			stockMove.setStockMoveLineList(new ArrayList<StockMoveLine>());
			
			for(PurchaseOrderLine purchaseOrderLine: purchaseOrder.getPurchaseOrderLineList()) {
				
				Product product = purchaseOrderLine.getProduct();
				// Check if the company field 'hasInSmForStorableProduct' = true and productTypeSelect = 'storable' or 'hasInSmForNonStorableProduct' = true and productTypeSelect = 'service' or productTypeSelect = 'other'
				if(product != null && ((company.getHasInSmForStorableProduct() && product.getProductTypeSelect().equals(IProduct.PRODUCT_TYPE_STORABLE)) 
						|| (company.getHasInSmForNonStorableProduct() && !product.getProductTypeSelect().equals(IProduct.PRODUCT_TYPE_STORABLE)))) {

					StockMoveLine stockMoveLine = stockMoveLineService.createStockMoveLine(product, purchaseOrderLine.getQty(), purchaseOrderLine.getUnit(), 
							purchaseOrderLineService.computeDiscount(purchaseOrderLine), stockMove, purchaseOrderLine.getProductVariant(), 2);
					if(stockMoveLine != null) {
						stockMove.getStockMoveLineList().add(stockMoveLine);
					}
				}	
			}
			if(stockMove.getStockMoveLineList() != null && !stockMove.getStockMoveLineList().isEmpty()){
				stockMoveService.plan(stockMove);
			}
		}
	}
	
	
	public Location getLocation(Company company)  {
		
		return Location.all().filter("company = ? and isDefaultLocation = ? and typeSelect = ?", company, true, ILocation.INTERNAL).fetchOne();
	}
}
