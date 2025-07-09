package com.akt.aether.deviceinfoservice.service;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import com.akt.aether.commons.enums.DeviceInfoType;
import com.akt.aether.commons.enums.DeviceState;
import com.akt.aether.commons.enums.DeviceType;
import com.akt.aether.commons.enums.Error;
import com.akt.aether.commons.enums.FileUploadType;
import com.akt.aether.commons.enums.MediaFileType;
import com.akt.aether.commons.enums.State;
import com.akt.aether.commons.enums.MediaFileStatusOnDevice;
import com.akt.aether.commons.exception.BusinessValidationException;
import com.akt.aether.commons.exception.InvalidRequestException;
import com.akt.aether.commons.util.ApplicationUtil;
import com.akt.aether.commons.validator.InputValidator;
import com.akt.aether.deviceinfoservice.entity.DeviceEntity;
import com.akt.aether.deviceinfoservice.entity.EventMediaFileRecordEntity;
import com.akt.aether.deviceinfoservice.entity.LoopMediaFileRecordEntity;
import com.akt.aether.deviceinfoservice.entity.TenantEntity;
import com.akt.aether.deviceinfoservice.model.DeviceInfo;
import com.akt.aether.deviceinfoservice.model.General;
import com.akt.aether.deviceinfoservice.model.Media;
import com.akt.aether.deviceinfoservice.model.Media.MediaDetails;
import com.akt.aether.deviceinfoservice.model.Media.MediaDetails.MediaFiles;
import com.akt.aether.deviceinfoservice.model.Media.MediaDetails.MediaFiles.FileInfo;
import com.akt.aether.deviceinfoservice.repo.DeviceRepository;
import com.akt.aether.deviceinfoservice.repo.EventMediaFileRecordRepository;
import com.akt.aether.deviceinfoservice.repo.LoopMediaFileRecordRepository;
import com.akt.aether.deviceinfoservice.repo.TenantRepository;
import com.akt.aether.deviceinfoservice.util.JsonUtil;
import com.akt.aether.deviceinfoservice.util.ValidatorUtil;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
public class DeviceInfoService extends Device {

	private static final Logger logger = LoggerFactory.getLogger(DeviceInfo.class);

	@Autowired
	private ValidatorUtil validator;

	@Autowired
	private EventMediaFileRecordRepository eventMediaFileRecordRepository;

	@Autowired
	private LoopMediaFileRecordRepository loopMediaFileRecordRepository;

	@Autowired
	private DeviceRepository deviceRepository;

	@Autowired
	private TenantRepository tenantRepository;

	@Override
	public DeviceInfo parseDeviceInfo(Message<byte[]> message) throws JsonProcessingException {
		logger.info("Parsing device info from message payload");
		DeviceInfo deviceInfo = JsonUtil.convertJsonToObject(new String(message.getPayload()), DeviceInfo.class);

		return deviceInfo;
	}

	@Override
	public boolean validateDeviceInfo(DeviceInfo deviceInfo, Message<byte[]> message, DeviceInfoType deviceInfoType)
			throws NoSuchAlgorithmException, InvalidRequestException, BusinessValidationException {

		logger.info("Validating Device Info Payload");
		InputValidator<DeviceInfo> inputValidator = new InputValidator<DeviceInfo>(deviceInfo);
		boolean isValidDeviceInfoPayload = validator.validateDeviceInfoDetails(deviceInfo, inputValidator,
				deviceInfoType);

		if (!isValidDeviceInfoPayload)
			return false;

		logger.info("Device Info Payload Validation Successful");

		validator.validateHeaderPayloadCommonFields(message, deviceInfo);

		isDeviceValid(deviceInfo);

		return isValidDeviceInfoPayload;
	}

	@Override
	public void processDeviceInfo(DeviceInfo deviceInfo, DeviceInfoType deviceInfoType)
			throws JsonProcessingException, BusinessValidationException {
		Optional<DeviceEntity> device = deviceRepository.findByDeviceSerialNumberAndDeviceType(
				deviceInfo.getDeviceSerialNumber(), DeviceType.fromType(deviceInfo.getDeviceType()));

		if (deviceInfoType.equals(DeviceInfoType.MEDIA)) {
			logger.info("Processing Media Info.");
			processMediaInfo(deviceInfo, device.get());
		} else if (deviceInfoType.equals(DeviceInfoType.GENERAL)) {
			logger.info("Processing General Info.");
			processGeneralInfo(deviceInfo, device.get());
		}

	}

	private void processGeneralInfo(DeviceInfo deviceInfo, DeviceEntity deviceEntity) {
		General generalInfo = (General) deviceInfo.getDeviceInfoDetails();
		String networkServiceProvider = generalInfo.getNetwork().getLte().getServiceProvider();
		Long simNumber = generalInfo.getNetwork().getLte().getSimNumber();

		deviceRepository.updateDeviceGeneralInfo(deviceEntity.getDeviceId(), networkServiceProvider, simNumber,
				LocalDateTime.now());
		logger.info("Updated General Info Successfully.");
	}

	private void processMediaInfo(DeviceInfo deviceInfo, DeviceEntity deviceEntity) throws BusinessValidationException {

		Media mediaInfo = (Media) deviceInfo.getDeviceInfoDetails();

		MediaDetails loopMediaDetails = mediaInfo.getLoopMedia();
		MediaDetails eventMediaDetails = mediaInfo.getEventMedia();

		if (eventMediaDetails != null) {
			logger.info("Processing Event Media Details.");
			processMediaDetails(eventMediaDetails, deviceEntity, FileUploadType.EVENT_MEDIA);
		}

		if (loopMediaDetails != null) {
			logger.info("Processing Loop Media Details.");
			processMediaDetails(loopMediaDetails, deviceEntity, FileUploadType.LOOP_MEDIA);
		}

		logger.info("Finished processing media info for deviceSerialNumber: {}", deviceInfo.getDeviceSerialNumber());
	}

	private void processMediaDetails(MediaDetails mediaDetails, DeviceEntity deviceEntity,
			FileUploadType fileUploadType) throws BusinessValidationException {

		MediaFiles videoFiles = mediaDetails.getVideoFiles();
		MediaFiles imageFiles = mediaDetails.getImageFiles();

		if (videoFiles != null) {

			logger.info("Processing Media Video Files.");
			processMediaFileInfo(videoFiles, MediaFileType.Video, fileUploadType, deviceEntity);
		}

		if (imageFiles != null) {

			logger.info("Processing Media Image Files.");
			processMediaFileInfo(imageFiles, MediaFileType.Image, fileUploadType, deviceEntity);

		}
	}

	private void processMediaFileInfo(MediaFiles mediaFiles, MediaFileType mediaFileType, FileUploadType fileUploadType,
			DeviceEntity deviceEntity) throws BusinessValidationException {

		logger.info("Processing media files for deviceSerialNumber: {}, mediaFileType: {}, fileUploadType: {}",
				deviceEntity.getDeviceSerialNumber(), mediaFileType, fileUploadType);
		processFiles(mediaFiles.getAdded(), MediaFileStatusOnDevice.ADDED, mediaFileType, fileUploadType, deviceEntity);
		processFiles(mediaFiles.getUploaded(), MediaFileStatusOnDevice.UPLOADED, mediaFileType, fileUploadType, deviceEntity);
		processFiles(mediaFiles.getDeleted(), MediaFileStatusOnDevice.DELETED, mediaFileType, fileUploadType, deviceEntity);
	}

	private void processFiles(List<FileInfo> fileInfos, MediaFileStatusOnDevice status, MediaFileType mediaFileType,
			FileUploadType fileUploadType, DeviceEntity deviceEntity) throws BusinessValidationException {

		if (fileInfos != null) {
			for (FileInfo fileInfo : fileInfos) {
				logger.info("Processing file '{}' with status: {} for deviceSerialNumber={}", fileInfo.getFilename(),
						status, deviceEntity.getDeviceSerialNumber());
				if (fileUploadType.equals(FileUploadType.EVENT_MEDIA)) {
					processEventMediaFile(fileInfo, status, mediaFileType, deviceEntity);
				} else {
					processLoopMediaFile(fileInfo, status, mediaFileType, deviceEntity);
				}
			}
		}
	}

	private void processEventMediaFile(FileInfo fileInfo, MediaFileStatusOnDevice statusOnDevice, MediaFileType mediaFileType,
			DeviceEntity deviceEntity) throws BusinessValidationException {

		String filename = fileInfo.getFilename();
		Long time = fileInfo.getTime();

		upsertEventMediaFileRecordEntity(filename, time, mediaFileType, deviceEntity, statusOnDevice);

	}

	private void processLoopMediaFile(FileInfo fileInfo, MediaFileStatusOnDevice statusOnDevice, MediaFileType mediaFileType,
			DeviceEntity deviceEntity) throws BusinessValidationException {

		String filename = fileInfo.getFilename();
		Long time = fileInfo.getTime();

		upsertLoopMediaFileRecordEntity(filename, time, mediaFileType, deviceEntity, statusOnDevice);

	}

	private void upsertLoopMediaFileRecordEntity(String loopMediaFilename, Long time, MediaFileType mediaType,
			DeviceEntity deviceEntity, MediaFileStatusOnDevice statusOnDevice) {

		logger.info(
				"Upserting LoopMediaFileRecordEntity for fileName: '{}', status: {}, mediaType: {}, deviceSerialNumber: {}",
				loopMediaFilename, statusOnDevice, mediaType, deviceEntity.getDeviceSerialNumber());

		Optional<LoopMediaFileRecordEntity> loopMediaFileRecordEntity = loopMediaFileRecordRepository
				.findByLoopMediaFileName(loopMediaFilename);

		if (loopMediaFileRecordEntity.isPresent()) {
			LoopMediaFileRecordEntity recordEntity = loopMediaFileRecordEntity.get();

			recordEntity.setLoopMediaFileType(mediaType.getMediaTypeId());
			recordEntity.setStatusOnDevice(statusOnDevice.getStatusOnDeviceId());
			if (MediaFileStatusOnDevice.ADDED.equals(statusOnDevice)) {
				recordEntity.setAddedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
			}
			if (MediaFileStatusOnDevice.DELETED.equals(statusOnDevice)) {
				recordEntity.setDeletedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
			}
			if (MediaFileStatusOnDevice.UPLOADED.equals(statusOnDevice)) {
				recordEntity.setUploadedToCloudTime(ApplicationUtil.epochMilliToLocalDateTime(time));
			}
			long millis = Instant.now().toEpochMilli();
			recordEntity.setUpdatedOn(ApplicationUtil.epochMilliToLocalDateTime(millis));
			loopMediaFileRecordRepository.save(recordEntity);
			logger.info("Successfully updated LoopMediaFileRecordEntity for fileName: '{}'", loopMediaFilename);
		} else {
			LoopMediaFileRecordEntity entity = generateLoopMediaFileRecordEntity(loopMediaFilename, time, mediaType,
					deviceEntity, statusOnDevice);
			loopMediaFileRecordRepository.save(entity);
			logger.info("Successfully inserted new LoopMediaFileRecordEntity for fileName: '{}'", loopMediaFilename);
		}

	}

	private LoopMediaFileRecordEntity generateLoopMediaFileRecordEntity(String loopMediaFilename, Long time,
			MediaFileType mediaType, DeviceEntity deviceEntity, MediaFileStatusOnDevice statusOnDevice) {

		LoopMediaFileRecordEntity entity = new LoopMediaFileRecordEntity();
		entity.setLoopMediaFileName(loopMediaFilename);
		entity.setDeviceId(deviceEntity.getDeviceId());
		entity.setDeviceSerialNumber(deviceEntity.getDeviceSerialNumber());
		entity.setTenantId(deviceEntity.getTenantId());
		entity.setLoopMediaFileType(mediaType.getMediaTypeId());
		entity.setStatusOnDevice(statusOnDevice.getStatusOnDeviceId());
		String[] parts = loopMediaFilename.split("_");
		entity.setLensId(Integer.parseInt(parts[2]));
		if (MediaFileStatusOnDevice.ADDED.equals(statusOnDevice)) {
			entity.setAddedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
		}
		if (MediaFileStatusOnDevice.DELETED.equals(statusOnDevice)) {
			entity.setDeletedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
		}
		if (MediaFileStatusOnDevice.UPLOADED.equals(statusOnDevice)) {
			entity.setUploadedToCloudTime(ApplicationUtil.epochMilliToLocalDateTime(time));
		}

		logger.info("Generated Loop Media File Record Entity for Filename: {}.", loopMediaFilename);
		return entity;
	}

	private void upsertEventMediaFileRecordEntity(String eventMediaFilename, Long time, MediaFileType mediaType,
			DeviceEntity deviceEntity, MediaFileStatusOnDevice statusOnDevice) {

		logger.info(
				"Upserting EventMediaFileRecordEntity for fileName: '{}', status: {}, mediaType: {}, deviceSerialNumber: {}",
				eventMediaFilename, statusOnDevice, mediaType, deviceEntity.getDeviceSerialNumber());

		Optional<EventMediaFileRecordEntity> eventMediaFileRecordEntity = eventMediaFileRecordRepository
				.findByEventMediaFileName(eventMediaFilename);

		if (eventMediaFileRecordEntity.isPresent()) {
			EventMediaFileRecordEntity recordEntity = eventMediaFileRecordEntity.get();
			recordEntity.setEventMediaFileType(mediaType.getMediaTypeId());
			recordEntity.setStatusOnDevice(statusOnDevice.getStatusOnDeviceId());
			if (MediaFileStatusOnDevice.ADDED.equals(statusOnDevice)) {
				recordEntity.setAddedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
			}
			if (MediaFileStatusOnDevice.DELETED.equals(statusOnDevice)) {
				recordEntity.setDeletedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
			}
			if (MediaFileStatusOnDevice.UPLOADED.equals(statusOnDevice)) {
				recordEntity.setUploadedToCloudTime(ApplicationUtil.epochMilliToLocalDateTime(time));
			}
			long millis = Instant.now().toEpochMilli();
			recordEntity.setUpdatedOn(ApplicationUtil.epochMilliToLocalDateTime(millis));
			eventMediaFileRecordRepository.save(recordEntity);
			logger.info("Successfully updated EventMediaFileRecordEntity for fileName='{}'", eventMediaFilename);

		} else {
			EventMediaFileRecordEntity entity = generateEventMediaRecordEntity(eventMediaFilename, time, mediaType,
					deviceEntity, statusOnDevice);
			eventMediaFileRecordRepository.save(entity);
			logger.info("Successfully inserted new EventMediaFileRecordEntity for fileName='{}'", eventMediaFilename);
		}

	}

	private EventMediaFileRecordEntity generateEventMediaRecordEntity(String eventMediaFilename, Long time,
			MediaFileType mediaType, DeviceEntity deviceEntity, MediaFileStatusOnDevice statusOnDevice) {

		EventMediaFileRecordEntity entity = new EventMediaFileRecordEntity();
		entity.setEventMediaFileName(eventMediaFilename);
		entity.setDeviceId(deviceEntity.getDeviceId());
		entity.setDeviceSerialNumber(deviceEntity.getDeviceSerialNumber());
		entity.setTenantId(deviceEntity.getTenantId());
		entity.setEventMediaFileType(mediaType.getMediaTypeId());
		entity.setStatusOnDevice(statusOnDevice.getStatusOnDeviceId());

		String[] parts = eventMediaFilename.split("_");
		entity.setLensId(Integer.parseInt(parts[2]));
		if (MediaFileStatusOnDevice.ADDED.equals(statusOnDevice)) {
			entity.setAddedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
		}
		if (MediaFileStatusOnDevice.DELETED.equals(statusOnDevice)) {
			entity.setDeletedOnDeviceTime(ApplicationUtil.epochMilliToLocalDateTime(time));
		}
		if (MediaFileStatusOnDevice.UPLOADED.equals(statusOnDevice)) {
			entity.setUploadedToCloudTime(ApplicationUtil.epochMilliToLocalDateTime(time));
		}

		logger.info("Generated Event Media File Record Entity for Filename: {}.", eventMediaFilename);
		return entity;
	}

	private void isDeviceValid(DeviceInfo deviceInfo) throws BusinessValidationException {
		Long deviceSerialNumber = deviceInfo.getDeviceSerialNumber();

		DeviceType deviceType = DeviceType.fromType(deviceInfo.getDeviceType());

		logger.info("Validating device details");
		Optional<DeviceEntity> device = deviceRepository.findByDeviceSerialNumberAndDeviceType(deviceSerialNumber,
				deviceType);
		if (!device.isPresent()) {
			throw new BusinessValidationException(Error.DEVICE_NOT_FOUND_ERROR, deviceSerialNumber);
		}
		DeviceEntity deviceEntity = device.get();
		logger.info("Device found with serial number: {} and device type: {}", deviceSerialNumber, deviceType);

		Integer tenantId = deviceEntity.getTenantId();

		if (tenantId == null) {
			throw new BusinessValidationException(Error.DEVICE_NOT_ASSOCIATED_WITH_TENANT, deviceSerialNumber);
		}

		logger.info("Device associated with tenant. Device serial number: {}", deviceSerialNumber);

		Optional<TenantEntity> tenantEntity = tenantRepository.findById(device.get().getTenantId());
		TenantEntity tenant = tenantEntity.get();
		logger.info("Tenant state {} for tenant ID: {}", tenant.getTenantState().name(), tenant.getTenantUniqueId());

		if (!tenant.getTenantState().equals(State.Active)) {
			throw new BusinessValidationException(Error.TENANT_INACTIVE_ERROR, tenant.getTenantUniqueId());
		}

		if (!device.get().getDeviceState().equals(DeviceState.Activated)) {
			throw new BusinessValidationException(Error.MEDIA_FILES_NOT_PROCESSED, device.get().getDeviceState());
		}

	}
}
