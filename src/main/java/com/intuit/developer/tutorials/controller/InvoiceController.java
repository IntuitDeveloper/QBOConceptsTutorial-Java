package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpSession;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.AccountClassificationEnum;
import com.intuit.ipp.data.AccountSubTypeEnum;
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.EmailAddress;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.data.Item;
import com.intuit.ipp.data.ItemTypeEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.LinkedTxn;
import com.intuit.ipp.data.Payment;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.SalesItemLineDetail;
import com.intuit.ipp.data.TxnTypeEnum;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;

/**
 * @author dderose
 *
 */
@Controller
public class InvoiceController {
	
	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;

	
	private static final Logger logger = Logger.getLogger(InvoiceController.class);
	
	
	/**
     * Sample QBO API call using OAuth2 tokens
     * 
     * @param session
     * @return
     */
	@ResponseBody
    @RequestMapping("/invoice")
    public String callInvoicingConcept(HttpSession session) {

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
        try {
        	
        	//get DataService
    		DataService service = helper.getDataService(realmId, accessToken);
    		
    		//add customer
    		Customer customer = getCustomerWithAllFields();
			Customer savedCustomer = service.add(customer);
    		
    		//add item
			Item item = getItemFields(service);
			Item savedItem = service.add(item);
    		
    		//create invoice using customer and item created above
			Invoice invoice = getInvoiceFields(savedCustomer, savedItem);
			Invoice savedInvoice = service.add(invoice);
    		
    		//send invoice email to customer
			service.sendEmail(savedInvoice, customer.getPrimaryEmailAddr().getAddress());
			
    		//receive payment for the invoice
			Payment payment = getPaymentFields(savedCustomer, savedInvoice);
			Payment savedPayment = service.add(payment);
			
			//return response back
			return processResponse(savedPayment);
			
		}
	        
        catch (InvalidTokenException e) {			
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response", "Failed").toString();
		}
		
    }
	
	private Customer getCustomerWithAllFields() {
		Customer customer = new Customer();
		customer.setDisplayName(RandomStringUtils.randomAlphanumeric(6));
		customer.setCompanyName("ABC Corporations");
		
		EmailAddress emailAddr = new EmailAddress();
		emailAddr.setAddress("testconceptsample@mailinator.com");
		customer.setPrimaryEmailAddr(emailAddr);

		return customer;
	}
	
	private Item getItemFields(DataService service) throws FMSException {

		Item item = new Item();
		item.setName("Item" + RandomStringUtils.randomAlphanumeric(5));
		item.setActive(true);
		item.setTaxable(false);
		item.setUnitPrice(new BigDecimal("200"));
		item.setType(ItemTypeEnum.SERVICE);

		Account incomeAccount = getIncomeBankAccount(service);
		
		ReferenceType accountRef = new ReferenceType();
		accountRef.setValue(incomeAccount.getId());
		item.setIncomeAccountRef(accountRef);
		
		return item;
	}
	
	private Account getIncomeBankAccount(DataService service) throws FMSException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.INCOME)) {
					return account;
				}
			}
		}
		return createIncomeBankAccount(service);
	}

	private Account createIncomeBankAccount(DataService service) throws FMSException {
		return service.add(getIncomeBankAccountFields());
	}
	
	private Account getIncomeBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Incom" + RandomStringUtils.randomAlphabetic(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.REVENUE);
		account.setAccountType(AccountTypeEnum.INCOME);
		account.setAccountSubType(AccountSubTypeEnum.SERVICE_FEE_INCOME.value());
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}
	
	private Invoice getInvoiceFields(Customer customer, Item item) {
		Invoice invoice = new Invoice();
		
		ReferenceType customerRef = new ReferenceType();
		customerRef.setValue(customer.getId());
		invoice.setCustomerRef(customerRef);
		
		List<Line> invLine = new ArrayList<Line>();
		Line line = new Line();
		line.setAmount(new BigDecimal("100"));
		line.setDetailType(LineDetailTypeEnum.SALES_ITEM_LINE_DETAIL);
		
		SalesItemLineDetail silDetails = new SalesItemLineDetail();
		
		ReferenceType itemRef = new ReferenceType();
		itemRef.setValue(item.getId());
		silDetails.setItemRef(itemRef);

		line.setSalesItemLineDetail(silDetails);
		invLine.add(line);
		invoice.setLine(invLine);
		
		return invoice;
	}
	
	private Payment getPaymentFields(Customer customer, Invoice invoice) {
		
		Payment payment = new Payment();
		
		ReferenceType customerRef = new ReferenceType();
		customerRef.setValue(customer.getId());
		payment.setCustomerRef(customerRef);
		
		payment.setTotalAmt(invoice.getTotalAmt());
		
		List<LinkedTxn> linkedTxnList = new ArrayList<LinkedTxn>();
		LinkedTxn linkedTxn = new LinkedTxn();
		linkedTxn.setTxnId(invoice.getId());
		linkedTxn.setTxnType(TxnTypeEnum.INVOICE.value());
		linkedTxnList.add(linkedTxn);
        
		Line line1 = new Line();
		line1.setAmount(invoice.getTotalAmt());
		line1.setLinkedTxn(linkedTxnList);
		
		List<Line> lineList = new ArrayList<Line>();
		lineList.add(line1);
		payment.setLine(lineList);

		return payment;
	}
	
	private String processResponse(Object entity) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			String jsonInString = mapper.writeValueAsString(entity);
			return jsonInString;
		} catch (JsonProcessingException e) {
			logger.error("Exception while getting company info ", e);
			return new JSONObject().put("response","Failed").toString();
		}
	}

    
}
