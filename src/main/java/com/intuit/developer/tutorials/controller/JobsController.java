package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
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
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.DiscountLineDetail;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.Estimate;
import com.intuit.ipp.data.IntuitEntity;
import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.data.Item;
import com.intuit.ipp.data.ItemTypeEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.LinkedTxn;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.SalesItemLineDetail;
import com.intuit.ipp.data.TxnTypeEnum;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;

/**
 * @author dderose
 *
 */
@Controller
public class JobsController {

	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;

	private static final Logger logger = Logger.getLogger(JobsController.class);
	
	private static final String MINOR_VERSION = "4";

	private static final String ACCOUNT_QUERY = "select * from Account where AccountType='%s' maxresults 1";

	
	
	/**
     * Sample QBO API call using OAuth2 tokens
     * 
     * @param session
     * @return
     */
	@ResponseBody
    @RequestMapping("/jobs")
    public String callJobsConcept(HttpSession session) {

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
        try {
        	
        	//get DataService
    		DataService service = helper.getDataService(realmId, accessToken, MINOR_VERSION);

			//add customer
			Customer customer = getCustomerWithMandatoryFields();
			Customer customerResult = service.add(customer);

			//add item
			Item item = getItemWithMandatoryFields(service);
			Item itemResult = service.add(item);
    		
    		//create estimate
			Estimate estimate = getEstimateWithMandatoryFields(itemResult, customerResult, service);
			Estimate createdEstimate = service.add(estimate);

			//update estimate -change amt
			createdEstimate.setTotalAmt(new BigDecimal("400.00"));
			Estimate updatedEstimate = service.update(createdEstimate);

			//create invoice using estimate data
			Invoice invoice = getInvoiceFieldsFromEstimate(updatedEstimate);
			Invoice createdInvoice = service.add(invoice);

			//update invoice to add discount
            Line discountLine = createDiscountLine(service);
			createdInvoice.getLine().add(discountLine);

			//update the invoice with the discount line
			Invoice updatedInvoice = service.update(createdInvoice);

			//return response back
    		return createResponse(updatedInvoice);
		} catch (InvalidTokenException e) {
			return new JSONObject().put("response","InvalidToken - Refresh token and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
    }
	
	/**
	 * Prepare Customer request
	 * 
	 * @return
	 */
	private Customer getCustomerWithMandatoryFields() {
		Customer customer = new Customer();
		customer.setDisplayName(RandomStringUtils.randomAlphanumeric(6));
		return customer;
	}
	

	/**
	 * Prepare Item request
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Item getItemWithMandatoryFields(DataService service) throws FMSException {
		Item item = new Item();
		item.setName("Item" + RandomStringUtils.randomAlphanumeric(5));
		item.setTaxable(false);
		item.setUnitPrice(new BigDecimal("200"));
		item.setType(ItemTypeEnum.SERVICE);

		Account incomeAccount = getIncomeBankAccount(service);
		item.setIncomeAccountRef(createRef(incomeAccount));
		return item;
	}
	
	/**
	 * Prepare Estimate request
	 * @param item
	 * @param customer
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Estimate getEstimateWithMandatoryFields(Item item, Customer customer, DataService service) throws FMSException {
		Estimate estimate = new Estimate();
	
		Line line1 = new Line();
		line1.setLineNum(new BigInteger("1"));
		line1.setAmount(new BigDecimal("300.00"));
		line1.setDetailType(LineDetailTypeEnum.SALES_ITEM_LINE_DETAIL);

		SalesItemLineDetail salesItemLineDetail1 = new SalesItemLineDetail();
		salesItemLineDetail1.setItemRef(createRef(item));
		line1.setSalesItemLineDetail(salesItemLineDetail1);

		List<Line> lines1 = new ArrayList<>();
		lines1.add(line1);
		estimate.setLine(lines1);

		estimate.setCustomerRef(createRef(customer));
		return estimate;
	}

	/**
	 * Prepare Invoice request
	 * @param estimate
	 * @return
	 */
	private Invoice getInvoiceFieldsFromEstimate(Estimate estimate) {
		Invoice invoice = new Invoice();
		invoice.setCustomerRef(estimate.getCustomerRef());
		
		invoice.setLine(estimate.getLine());
		
		List<LinkedTxn> linkedTxnList = new ArrayList<LinkedTxn>();
		LinkedTxn linkedTxn = new LinkedTxn();
		linkedTxn.setTxnId(estimate.getId());
		linkedTxn.setTxnType(TxnTypeEnum.ESTIMATE.value());
		linkedTxnList.add(linkedTxn);
		
		invoice.setLinkedTxn(linkedTxnList);

		return invoice;
	}


    /**
     * Create DiscountLineDetail object
     * 
     * @param service
     * @return
     * @throws FMSException
     */
    private Line createDiscountLine(DataService service) throws FMSException {
        DiscountLineDetail discountLineDetail = new DiscountLineDetail();

        discountLineDetail.setPercentBased(false);
        discountLineDetail.setDiscountAccountRef(createRef(getIncomeBankAccount(service)));

        Line discountLine = new Line();

        discountLine.setDiscountLineDetail(discountLineDetail);
        discountLine.setDetailType(LineDetailTypeEnum.DISCOUNT_LINE_DETAIL);
        discountLine.setAmount(new BigDecimal("10.00"));
        return discountLine;
    }


	/**
	 * Get Income Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account getIncomeBankAccount(DataService service) throws FMSException {
		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.INCOME.value()));
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
		account.setName("Incom" + RandomStringUtils.randomAlphabetic(5));
		account.setAccountType(AccountTypeEnum.INCOME);
		
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
