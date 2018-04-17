package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpSession;

import com.intuit.ipp.data.*;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.util.DateUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;

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

    		// Create OR Fetch BankAccount
            Account savedAccount = getBankAccount(service);

            // Create OR Fetch CreditCaerd Account
            Account savedCreditAccount = getCreditCardBankAccount(service);
    		
    		// Create Journal Entry using the accounts above

			try{
			    // Add JournalEntry
                JournalEntry journalentry = getJournalEntryFields(service, savedAccount, savedCreditAccount);
                JournalEntry savedJournalEntry = service.add(journalentry);
                logger.info("JournalEntry created: " + savedJournalEntry.getId());

                // Return the result
                String result = "Journal Entry = " + savedJournalEntry.getId();

                return result;

			} catch (Exception e) {
                logger.error("Error while calling entity add:: " + e.getMessage());
            }

    		return "";
			
		}
	        
        catch (InvalidTokenException e) {			
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
    }



    /**
     * Initializes a BankAccount object
     *
     * @return BankAccount object
     * @throws FMSException
     */
	private static Account getBankAccountFields() throws FMSException {

        Account account = new Account();
		account.setName("Ba" + RandomStringUtils.randomAlphanumeric(7));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.ASSET);
		account.setAccountType(AccountTypeEnum.BANK);
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		account.setTxnLocationType("FranceOverseas");
		account.setAcctNum("B" + RandomStringUtils.randomAlphanumeric(6));

		return account;
	}

    /**
     * Create OR lookup Bank Account
     *
     * @param service Reference to the DataService to create the Account
     * @return The BankAccount object
     * @throws FMSException
     */
    private static Account getBankAccount(DataService service) throws FMSException {

        List<Account> accounts = (List<Account>) service.findAll(new Account());

        if (!accounts.isEmpty()) {
            Iterator<Account> itr = accounts.iterator();
            while (itr.hasNext()) {
                Account account = itr.next();
                if (account.getAccountType().equals(AccountTypeEnum.BANK)) {
                    return account;
                }
            }
        }

        return createBankAccount(service);
    }

    /**
     * Creates the BankAccount by calling QBO V3 services
     *
     * @param service Reference to the DataService to create the Account
     * @return The BankAccount object
     * @throws FMSException
     */
    private static Account createBankAccount(DataService service) throws FMSException {
        return service.add(getBankAccountFields());
    }

    /**
     * Create OR lookup CreditCard Account
     *
     * @param service Reference to the DataService to create the Account
     * @return The CreditCard account
     * @throws FMSException
     */
	private static Account getCreditCardBankAccount(DataService service) throws FMSException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.CREDIT_CARD)) {
					return account;
				}
			}
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
	private static Account createCreditCardBankAccount(DataService service) throws FMSException {
		return service.add(getCreditCardBankAccountFields());
	}

    /**
     * Initializes a CreditCard Account object
     *
     * @return The CreditCard account object
     * @throws FMSException
     */
	private static Account getCreditCardBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("CreditCa" + RandomStringUtils.randomAlphabetic(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.LIABILITY);
		account.setAccountType(AccountTypeEnum.CREDIT_CARD);
		account.setAccountSubType(AccountSubTypeEnum.CREDIT_CARD.value());
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}


	// Journal Entry methods

    /**
     * Creates a JournalEntry against the given BankAccount and CreditCard Account
     * These accounts are added as 2 line items
     * This method internally creates or lookup a Vendor
     *
     * @param service Reference to the DataService to create the JournalEntry
     * @param bankAccount The BankAccount reference
     * @param creditAccount The CreditAccount reference
     * @return Reference to the created JournalEntry
     * @throws FMSException
     * @throws ParseException
     */
	private static JournalEntry getJournalEntryFields(DataService service, Account bankAccount, Account creditAccount) throws FMSException, ParseException {

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

		journalEntryLineDetail1.setAccountRef(getAccountRef(bankAccount));

		line1.setJournalEntryLineDetail(journalEntryLineDetail1);
		line1.setDescription("Description " + RandomStringUtils.randomAlphanumeric(15));
		line1.setAmount(new BigDecimal("100.00"));

		// Create LineItem 2 - Credit
		Line line2 = new Line();
		line2.setDetailType(LineDetailTypeEnum.JOURNAL_ENTRY_LINE_DETAIL);
		JournalEntryLineDetail journalEntryLineDetail2 = new JournalEntryLineDetail();
		journalEntryLineDetail2.setPostingType(PostingTypeEnum.CREDIT);

		journalEntryLineDetail2.setAccountRef(getAccountRef(creditAccount));
		EntityTypeRef eRef = new EntityTypeRef();
		eRef.setType(EntityTypeEnum.VENDOR);
		eRef.setEntityRef(getVendorRef(getVendor(service)));
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
     * Fetch the Account Reference to the given Account
     *
     * @param account The Account object
     * @return Account reference
     */
	private static ReferenceType getAccountRef(Account account) {
		ReferenceType accountRef = new ReferenceType();
		accountRef.setName(account.getName());
		accountRef.setValue(account.getId());
		return accountRef;
	}

    /**
     * Create OR lookup Vendor
     *
     * @param service Reference to the DataService to create the Vendor
     * @return Vendor object
     * @throws FMSException
     * @throws ParseException
     */
	private static Vendor getVendor(DataService service) throws FMSException, ParseException {
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
	private static Vendor createVendor(DataService service) throws FMSException, ParseException {
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
	private static Vendor getVendorWithAllFields(DataService service) throws FMSException, ParseException {
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
     * Fetch the Vendor Reference to the given Vendor
     *
     * @param vendor The Vendor object
     * @return Vendor reference
     */
    private static ReferenceType getVendorRef(Vendor vendor) {
		ReferenceType vendorRef = new ReferenceType();
		vendorRef.setName(vendor.getDisplayName());
		vendorRef.setValue(vendor.getId());
		return vendorRef;
	}
}