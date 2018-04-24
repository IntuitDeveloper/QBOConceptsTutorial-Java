package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.RandomStringUtils;
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
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.AccountSubTypeEnum;
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.EmailAddress;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.IntuitEntity;
import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.data.Item;
import com.intuit.ipp.data.ItemTypeEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.SalesItemLineDetail;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;

/**
 * @author bcole
 *
 */
@Controller
public class InventoryController {

	@Autowired
	OAuth2PlatformClientFactory factory;

	@Autowired
	public QBOServiceHelper helper;

	private static final Logger logger = Logger.getLogger(InventoryController.class);
	
	private static final String ACCOUNT_QUERY = "select * from Account where AccountType='%s' and AccountSubType='%s' maxresults 1";


	/**
	 * Sample QBO API call using OAuth2 tokens
	 *
	 * @param session
	 * @return
	 */
	@ResponseBody
	@RequestMapping("/inventory")
	public String callInventoryConcept(HttpSession session) {

		String realmId = (String)session.getAttribute("realmId");
		if (StringUtils.isEmpty(realmId)) {
			return new JSONObject().put("response", "No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
		}
		String accessToken = (String)session.getAttribute("access_token");

		try {

			// Get DataService
			DataService service = helper.getDataService(realmId, accessToken);

			// Add inventory item - with initial Quantity on Hand of 10
			Item item = getItemWithAllFields(service);
			Item savedItem = service.add(item);

			// Create invoice (for 1 item) using the item created above
			Customer customer = getCustomerWithAllFields();
			Customer savedCustomer = service.add(customer);
			Invoice invoice = getInvoiceFields(savedCustomer, savedItem);
			service.add(invoice);

			// Query inventory item - there should be 9 items now!
			Item itemsRemaining = service.findById(savedItem);

			// Return response back - take a look at "qtyOnHand" in the output (should be 9)
			return createResponse(itemsRemaining);

		} catch (InvalidTokenException e) {
			return new JSONObject().put("response", "InvalidToken - Refresh token and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
	}


	/**
	 * Prepare Item request
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Item getItemWithAllFields(DataService service) throws FMSException {
		Item item = new Item();
		item.setType(ItemTypeEnum.INVENTORY);
		item.setName("Inventory Item " + RandomStringUtils.randomAlphanumeric(5));
		item.setInvStartDate(new Date());

		// Start with 10 items
		item.setQtyOnHand(BigDecimal.valueOf(10));
		item.setTrackQtyOnHand(true);

		Account incomeBankAccount = getIncomeBankAccount(service);
		item.setIncomeAccountRef(createRef(incomeBankAccount));

		Account expenseBankAccount = getExpenseBankAccount(service);
		item.setExpenseAccountRef(createRef(expenseBankAccount));

		Account assetAccount = getAssetAccount(service);
		item.setAssetAccountRef(createRef(assetAccount));

		return item;
	}

	/**
	 * Prepare Customer request
	 * @return
	 */
	private Customer getCustomerWithAllFields() {
		Customer customer = new Customer();
		customer.setDisplayName(org.apache.commons.lang.RandomStringUtils.randomAlphanumeric(6));
		customer.setCompanyName("ABC Corporations");

		EmailAddress emailAddr = new EmailAddress();
		emailAddr.setAddress("testconceptsample@mailinator.com");
		customer.setPrimaryEmailAddr(emailAddr);

		return customer;
	}

	/**
	 * Prepare Invoice Request
	 * @param customer
	 * @param item
	 * @return
	 */
	private Invoice getInvoiceFields(Customer customer, Item item) {
		Invoice invoice = new Invoice();
		invoice.setCustomerRef(createRef(customer));

		List<Line> invLine = new ArrayList<Line>();
		Line line = new Line();
		line.setAmount(new BigDecimal("100"));
		line.setDetailType(LineDetailTypeEnum.SALES_ITEM_LINE_DETAIL);

		SalesItemLineDetail silDetails = new SalesItemLineDetail();
		silDetails.setQty(BigDecimal.valueOf(1));
		silDetails.setItemRef(createRef(item));

		line.setSalesItemLineDetail(silDetails);
		invLine.add(line);
		invoice.setLine(invLine);

		return invoice;
	}



	/**
	 * Get Income Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account getIncomeBankAccount(DataService service) throws FMSException {
		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.INCOME.value(), AccountSubTypeEnum.SALES_OF_PRODUCT_INCOME.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}
		return createIncomeBankAccount(service);
	}

	/**
	 * Create Income Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account createIncomeBankAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("Income " + RandomStringUtils.randomAlphabetic(5));
		account.setAccountType(AccountTypeEnum.INCOME);
		account.setAccountSubType(AccountSubTypeEnum.SALES_OF_PRODUCT_INCOME.value());
		
		return service.add(account);
	}

	/**
	 * Get Expense Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account getExpenseBankAccount(DataService service) throws FMSException {
		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.COST_OF_GOODS_SOLD.value(), AccountSubTypeEnum.SUPPLIES_MATERIALS_COGS.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}
		return createExpenseBankAccount(service);
	}

	/**
	 * Create Expense Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account createExpenseBankAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("Expense " + RandomStringUtils.randomAlphabetic(5));
		account.setAccountType(AccountTypeEnum.COST_OF_GOODS_SOLD);
		account.setAccountSubType(AccountSubTypeEnum.SUPPLIES_MATERIALS_COGS.value());
		
		return service.add(account);
	}


	/**
	 * Get Asset Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account getAssetAccount(DataService service)  throws FMSException{
		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.OTHER_CURRENT_ASSET.value(), AccountSubTypeEnum.INVENTORY.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}
		return createOtherCurrentAssetAccount(service);
	}

	/**
	 * Create Asset Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account createOtherCurrentAssetAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("Other Current Asset " + RandomStringUtils.randomAlphanumeric(5));
		account.setAccountType(AccountTypeEnum.OTHER_CURRENT_ASSET);
		account.setAccountSubType(AccountSubTypeEnum.INVENTORY.value());
		
		return service.add(account);
	}

	/**
	 * Creates reference type for an entity
	 * 
	 * @param entity - IntuitEntity object inherited by each entity
	 * @return
	 */
	private ReferenceType createRef(IntuitEntity entity) {
		ReferenceType referenceType = new ReferenceType();
		referenceType.setValue(entity.getId());
		return referenceType;
	}

	/**
	 * Map object to json string
	 * @param entity
	 * @return
	 */
	private String createResponse(Object entity) {
		ObjectMapper mapper = new ObjectMapper();
		String jsonInString;
		try {
			jsonInString = mapper.writeValueAsString(entity);
		} catch (JsonProcessingException e) {
			return createErrorResponse(e);
		} catch (Exception e) {
			return createErrorResponse(e);
		}
		return jsonInString;
	}

	private String createErrorResponse(Exception e) {
		logger.error("Exception while calling QBO ", e);
		return new JSONObject().put("response","Failed").toString();
	}


}
