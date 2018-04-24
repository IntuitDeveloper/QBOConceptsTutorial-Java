package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
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
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.AccountClassificationEnum;
import com.intuit.ipp.data.AccountSubTypeEnum;
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.EntityTypeEnum;
import com.intuit.ipp.data.EntityTypeRef;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.IntuitEntity;
import com.intuit.ipp.data.JournalEntry;
import com.intuit.ipp.data.JournalEntryLineDetail;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.PostingTypeEnum;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.Vendor;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;
import com.intuit.ipp.util.DateUtils;

/**
 * @author dderose
 *
 */
@Controller
public class AccountingController {
	
	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;
	
	private static final Logger logger = Logger.getLogger(AccountingController.class);
	
	private static final String ACCOUNT_QUERY = "select * from Account where AccountType='%s' maxresults 1";
	
	
	/**
     * Sample QBO API call using OAuth2 tokens. This method creates 2 Accounts (1 Bank Account and 1 CreditCard Account),
     * followed by a JournalEntry using the above accounts.
     * 
     * @param session Reference to the surrent HTTP Session
     * @return Result of this operation
     */
	@ResponseBody
    @RequestMapping("/accounting")
    public String callAccountingConcept(HttpSession session) {

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
    	try {
        	
        	// Get DataService
    		DataService service = helper.getDataService(realmId, accessToken);

    		// Create OR Fetch DebitAccount
            Account savedDebitAccount = getDebitAccount(service);

            // Create OR Fetch CreditCard Account
            Account savedCreditAccount = getCreditCardBankAccount(service);
    		
    		// Create Journal Entry using the accounts above
            JournalEntry journalentry = getJournalEntryFields(service, savedDebitAccount, savedCreditAccount);
            JournalEntry savedJournalEntry = service.add(journalentry);
            logger.info("JournalEntry created: " + savedJournalEntry.getId());

            // Return the result
    		return createResponse(savedJournalEntry);
			
		}
	        
        catch (InvalidTokenException e) {			
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		} catch (ParseException e) {
			return new JSONObject().put("response","Parse Exception").toString();
		}
    }


    /**
     * Create OR lookup Debit Account
     *
     * @param service Reference to the DataService to create the Account
     * @return The BankAccount object
     * @throws FMSException
     */
    private Account getDebitAccount(DataService service) throws FMSException {

    	QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.BANK.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}

        return createDebitAccount(service);
    }

    /**
     * Creates the DebitAccount by calling QBO V3 services
     *
     * @param service Reference to the DataService to create the Account
     * @return The DebitAccount object
     * @throws FMSException
     */
    private Account createDebitAccount(DataService service) throws FMSException {
    	 Account account = new Account();
 		account.setName("Ba" + RandomStringUtils.randomAlphanumeric(7));
 		account.setClassification(AccountClassificationEnum.ASSET);
 		account.setAccountType(AccountTypeEnum.BANK);
 		
        return service.add(account);
    }

    /**
     * Create OR lookup CreditCard Account
     *
     * @param service Reference to the DataService to create the Account
     * @return The CreditCard account
     * @throws FMSException
     */
	private Account getCreditCardBankAccount(DataService service) throws FMSException {
		
		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.CREDIT_CARD.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}

		return createCreditCardBankAccount(service);
	}

    /**
     * Creates the CreditCard Account by calling QBO V3 services
     *
     * @param service Reference to the DataService to create the Account
     * @return The CreditCard account returned by the QBO V3 Service
     * @throws FMSException
     */
	private Account createCreditCardBankAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("CreditCa" + RandomStringUtils.randomAlphabetic(5));
		account.setClassification(AccountClassificationEnum.LIABILITY);
		account.setAccountType(AccountTypeEnum.CREDIT_CARD);
		account.setAccountSubType(AccountSubTypeEnum.CREDIT_CARD.value());
		
		return service.add(account);
	}



	// Journal Entry methods

    /**
     * Creates a JournalEntry against the given BankAccount and CreditCard Account
     * These accounts are added as 2 line items
     * This method internally creates or lookup a Vendor
     *
     * @param service Reference to the DataService to create the JournalEntry
     * @param debitAccount The BankAccount reference
     * @param creditAccount The CreditAccount reference
     * @return Reference to the created JournalEntry
     * @throws FMSException
     * @throws ParseException
     */
	private JournalEntry getJournalEntryFields(DataService service, Account debitAccount, Account creditAccount) throws FMSException, ParseException {

	    JournalEntry journalEntry = new JournalEntry();
		try {
			journalEntry.setTxnDate(DateUtils.getCurrentDateTime());
		} catch (ParseException e) {
			throw new FMSException("ParseException while getting current date.");
		}

		// Create LineItem 1 - Debit
		Line line1 = new Line();
		line1.setDetailType(LineDetailTypeEnum.JOURNAL_ENTRY_LINE_DETAIL);
		JournalEntryLineDetail journalEntryLineDetail1 = new JournalEntryLineDetail();
		journalEntryLineDetail1.setPostingType(PostingTypeEnum.DEBIT);

		journalEntryLineDetail1.setAccountRef(createRef(debitAccount));

		line1.setJournalEntryLineDetail(journalEntryLineDetail1);
		line1.setDescription("Description " + RandomStringUtils.randomAlphanumeric(15));
		line1.setAmount(new BigDecimal("100.00"));

		// Create LineItem 2 - Credit
		Line line2 = new Line();
		line2.setDetailType(LineDetailTypeEnum.JOURNAL_ENTRY_LINE_DETAIL);
		JournalEntryLineDetail journalEntryLineDetail2 = new JournalEntryLineDetail();
		journalEntryLineDetail2.setPostingType(PostingTypeEnum.CREDIT);

		journalEntryLineDetail2.setAccountRef(createRef(creditAccount));
		EntityTypeRef eRef = new EntityTypeRef();
		eRef.setType(EntityTypeEnum.VENDOR);
		eRef.setEntityRef(createRef(getVendor(service)));    // Set a Vendor as reference
		journalEntryLineDetail2.setEntity(eRef);

		line2.setJournalEntryLineDetail(journalEntryLineDetail2);
		line2.setDescription("Description " + RandomStringUtils.randomAlphanumeric(15));
		line2.setAmount(new BigDecimal("100.00"));

		List<Line> lines1 = new ArrayList<Line>();
		lines1.add(line1);
		lines1.add(line2);
		journalEntry.setLine(lines1);
		journalEntry.setPrivateNote("Journal Entry");
		journalEntry.setDomain("QBO");

		return journalEntry;
	}


    /**
     * Create OR lookup Vendor
     *
     * @param service Reference to the DataService to create the Vendor
     * @return Vendor object
     * @throws FMSException
     * @throws ParseException
     */
	private Vendor getVendor(DataService service) throws FMSException, ParseException {
		List<Vendor> vendors = (List<Vendor>) service.findAll(new Vendor());

		if (!vendors.isEmpty()) {
			return vendors.get(0);
		}
		return createVendor(service);
	}

    /**
     * Creates the Vendor by calling QBO V3 services
     *
     * @param service Reference to the DataService to create the Vendor
     * @return Created Vendor object
     * @throws FMSException
     * @throws ParseException
     */
	private Vendor createVendor(DataService service) throws FMSException, ParseException {
		return service.add(getVendorWithAllFields(service));
	}

    /**
     * Initializes a Vendor object
     *
     * @param service Reference to the DataService to create the Account
     * @return Vendor object
     * @throws FMSException
     * @throws ParseException
     */
	private Vendor getVendorWithAllFields(DataService service) throws FMSException, ParseException {
		Vendor vendor = new Vendor();
		// Mandatory Fields
		vendor.setDisplayName(RandomStringUtils.randomAlphanumeric(8));

		try {
			vendor.setOpenBalanceDate(DateUtils.getCurrentDateTime());
		} catch (ParseException e) {
			throw new FMSException("ParseException while getting current date.");
		}

		return vendor;

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