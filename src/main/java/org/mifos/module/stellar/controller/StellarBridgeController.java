/**
 * Copyright 2016 Myrle Krantz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mifos.module.stellar.controller;

import com.google.gson.Gson;
import org.mifos.module.stellar.federation.FederationFailedException;
import org.mifos.module.stellar.federation.InvalidStellarAddressException;
import org.mifos.module.stellar.federation.StellarAddress;
import org.mifos.module.stellar.persistencedomain.PaymentPersistency;
import org.mifos.module.stellar.restdomain.AccountBridgeConfiguration;
import org.mifos.module.stellar.restdomain.AmountConfiguration;
import org.mifos.module.stellar.restdomain.JournalEntryData;
import org.mifos.module.stellar.restdomain.TrustLineConfiguration;
import org.mifos.module.stellar.service.*;
import org.mifos.module.stellar.service.SecurityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLDecoder;

@RestController()
@RequestMapping("/modules/stellarbridge")
public class StellarBridgeController {
  private static final String API_KEY_HEADER_LABEL = "X-Stellar-Bridge-API-Key";
  private static final String TENANT_ID_HEADER_LABEL = "X-Mifos-Platform-TenantId";

  private final SecurityService securityService;
  private StellarBridgeService stellarBridgeService;
  private final JournalEntryPaymentMapper journalEntryPaymentMapper;
  private final Gson gson;

  @Autowired
  public StellarBridgeController(
      final SecurityService securityService,
      final StellarBridgeService stellarBridgeService,
      final JournalEntryPaymentMapper journalEntryPaymentMapper,
      final Gson gson)
  {
    this.securityService = securityService;
    this.stellarBridgeService = stellarBridgeService;
    this.journalEntryPaymentMapper = journalEntryPaymentMapper;
    this.gson = gson;
  }

  @RequestMapping(value = "", method = RequestMethod.POST,
                  consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<String> createStellarBridgeConfiguration(
      @RequestBody final AccountBridgeConfiguration stellarBridgeConfig)
  {
    final String mifosTenantId = stellarBridgeConfig.getMifosTenantId();
    final String newApiKey = this.securityService.generateApiKey(mifosTenantId);
    stellarBridgeService.createStellarBridgeConfig(
        stellarBridgeConfig.getMifosTenantId(),
        stellarBridgeConfig.getMifosToken());

    return new ResponseEntity<>(newApiKey, HttpStatus.CREATED);
  }

  @RequestMapping(value = "", method = RequestMethod.DELETE,
      produces = {"application/json"})
  public ResponseEntity<Void> deleteAccountBridgeConfiguration(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId)
  {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);
    this.securityService.removeApiKey(mifosTenantId);

    if (stellarBridgeService.deleteAccountBridgeConfig(mifosTenantId))
    {
      return new ResponseEntity<>(HttpStatus.OK);
    }
    else
    {
      return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
  }

  @RequestMapping(
      value = "/trustlines/{assetCode}/{issuer}/",
      method = RequestMethod.PUT,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<Void> adjustTrustLine(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId,
      @PathVariable("assetCode") final String trustedAssetCode,
      @PathVariable("issuer") final String urlEncodedIssuingStellarAddress,
      @RequestBody final TrustLineConfiguration stellarTrustLineConfig)
      throws InvalidStellarAddressException
  {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);


    final String issuingStellarAddress;
    try {
      issuingStellarAddress = URLDecoder.decode(urlEncodedIssuingStellarAddress, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    this.stellarBridgeService.adjustTrustLine(
        mifosTenantId,
        StellarAddress.parse(issuingStellarAddress),
        trustedAssetCode,
        stellarTrustLineConfig.getMaximumAmount());

    return new ResponseEntity<>(HttpStatus.OK);
  }

  @RequestMapping(value = "/vault/{assetCode}",
      method = RequestMethod.PUT,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<BigDecimal> adjustVaultIssuedAssets(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId,
      @PathVariable("assetCode") final String assetCode,
      @RequestBody final AmountConfiguration amountConfiguration)
  {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);
    //TODO: add security for currency issuing.
    final BigDecimal amount = amountConfiguration.getAmount();
    if (amount.compareTo(BigDecimal.ZERO) < 0)
    {
      return new ResponseEntity<>(amount, HttpStatus.BAD_REQUEST);
    }

    final BigDecimal amountAdjustedTo
        = stellarBridgeService.adjustVaultIssuedAssets(mifosTenantId, assetCode, amount);

    if (amountAdjustedTo.compareTo(amount) != 0)
      return new ResponseEntity<>(amountAdjustedTo, HttpStatus.CONFLICT);
    else
      return new ResponseEntity<>(amountAdjustedTo, HttpStatus.OK);
  }

  @RequestMapping(value = "/vault/{assetCode}",
      method = RequestMethod.GET,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<BigDecimal> getVaultIssuedAssets(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId,
      @PathVariable("assetCode") final String assetCode)
  {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);

    if (!stellarBridgeService.tenantHasVault(mifosTenantId))
      return new ResponseEntity<>(HttpStatus.NOT_FOUND);

    final BigDecimal vaultIssuedAssets
        = stellarBridgeService.getVaultIssuedAssets(mifosTenantId, assetCode);

    return new ResponseEntity<>(vaultIssuedAssets, HttpStatus.OK);
  }

  @RequestMapping(
      value = "/balances/{assetCode}",
      method = RequestMethod.GET,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<BigDecimal> getStellarAccountBalance(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId,
      @PathVariable("assetCode") final String assetCode)
  {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);

    return new ResponseEntity<>(
        stellarBridgeService.getBalance(mifosTenantId, assetCode),
        HttpStatus.OK);
  }

  @RequestMapping(
      value = "/balances/{assetCode}/{issuer}/",
      method = RequestMethod.GET,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<BigDecimal> getStellarAccountBalanceByIssuer(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId,
      @PathVariable("assetCode") final String assetCode,
      @PathVariable("issuer") final String urlEncodedIssuingStellarAddress)
  {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);

    final String issuingStellarAddress;
    try {
      issuingStellarAddress = URLDecoder.decode(urlEncodedIssuingStellarAddress, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    return new ResponseEntity<>(
        stellarBridgeService.getBalanceByIssuer(
            mifosTenantId, assetCode,
            StellarAddress.parse(issuingStellarAddress)),
        HttpStatus.OK);
  }

  @RequestMapping(value = "/payments/", method = RequestMethod.POST,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<Void> sendStellarPayment(
      @RequestHeader(API_KEY_HEADER_LABEL) final String apiKey,
      @RequestHeader(TENANT_ID_HEADER_LABEL) final String mifosTenantId,
      @RequestHeader("X-Mifos-Entity") final String entity,
      @RequestHeader("X-Mifos-Action") final String action,
      @RequestBody final String payload)
      throws SecurityException {
    this.securityService.verifyApiKey(apiKey, mifosTenantId);

    if (entity.equalsIgnoreCase("JOURNALENTRY")
        && action.equalsIgnoreCase("CREATE"))
    {
      final JournalEntryData journalEntry = gson.fromJson(payload, JournalEntryData.class);

      final PaymentPersistency payment =
          journalEntryPaymentMapper.mapToPayment(mifosTenantId, journalEntry);

      if (!payment.isStellarPayment)
      {
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
      }

      this.stellarBridgeService.sendPaymentToStellar(payment);
    }

    return new ResponseEntity<>(HttpStatus.ACCEPTED);
  }

  @RequestMapping(
      value = "/installationaccount/balances/{assetCode}/{issuer}/",
      method = RequestMethod.GET,
      consumes = {"application/json"}, produces = {"application/json"})
  public ResponseEntity<BigDecimal> getInstallationAccountBalance(
      @PathVariable("assetCode") final String assetCode,
      @PathVariable("issuer") final String issuingStellarAddress)
  {
    return new ResponseEntity<>(
        stellarBridgeService.getInstallationAccountBalance(
            assetCode,
            StellarAddress.parse(issuingStellarAddress)
        ),
        HttpStatus.OK);

  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public String handleInvalidApiKeyException(
      final SecurityException ex) {
    return ex.getMessage();
  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String handleInvalidConfigurationException(
      final InvalidConfigurationException ex) {
    return ex.getMessage();
  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String handleStellarAccountCreationFailedException(
      final StellarAccountCreationFailedException ex) {
    return ex.getMessage();
  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String handleInvalidStellarAddressException(
      final InvalidStellarAddressException ex) {
    return ex.getMessage();
  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String handleFederationFailedException(
      final FederationFailedException ex) {
    return ex.getMessage();
  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public String handleInvalidJournalEntryException(
      final InvalidJournalEntryException ex) {
    return ex.getMessage();
  }

  @ExceptionHandler
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public String handleStellarCreditLineCreationFailedException(
      final StellarTrustLineAdjustmentFailedException ex)
  {
    return ex.getMessage();
    //TODO: figure out how to communicate missing funds problem to user.
  }
}
