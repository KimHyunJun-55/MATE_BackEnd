package MATE.Carpool.domain.member.service;


import MATE.Carpool.common.PKEncryption;
import MATE.Carpool.common.exception.CustomException;
import MATE.Carpool.common.exception.ErrorCode;
import MATE.Carpool.config.jwt.JwtProvider;
import MATE.Carpool.config.jwt.JwtTokenDto;
import MATE.Carpool.config.jwt.RefreshToken;
import MATE.Carpool.config.jwt.RefreshTokenRepository;
import MATE.Carpool.config.userDetails.CustomUserDetails;
import MATE.Carpool.domain.member.dto.request.SocialMemberInfoDto;
import MATE.Carpool.domain.member.dto.response.KakaoTokenResponseDto;
import MATE.Carpool.domain.member.dto.response.MemberResponseDto;
import MATE.Carpool.domain.member.entity.Member;
import MATE.Carpool.domain.member.entity.ProviderType;
import MATE.Carpool.domain.member.repository.MemberRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderValues;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class OauthService {

    @Value("${oauth.line.client_id}")
    private String lineClientId;
    @Value("${oauth.kakao.client_id}")
    private String kakaoClientId;

    @Value("${oauth.line.secret_id}")
    private String lineSecretKey;
    @Value("${oauth.kakao.secret_id}")
    private String kakaoSecretKey;

    private final JwtProvider jwtProvider;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    private final static String KAKAO_TOKEN_URL_HOST="https://kauth.kakao.com";
    private final static String LINE_TOKEN_URL_HOST="https://api.line.me/oauth2/v2.1/token";


    public ResponseEntity<MemberResponseDto> socialLogin(String provider,String code, HttpServletResponse response ) throws JsonProcessingException {

        String accessKey = getAccessKey(provider, code, response);

        SocialMemberInfoDto socialMemberInfoDto = getMemberInfo(provider , accessKey);

        Member member  = registerMember(provider , socialMemberInfoDto);

        Authentication authentication = forceLogin(member);

        jwtProvider.createTokenAndSavedRefreshHttponly(authentication,response,member.getMemberId());

        MemberResponseDto memberResponseDto = new MemberResponseDto(member);

        log.info(memberResponseDto.toString());

        return  ResponseEntity.ok(memberResponseDto);

    }

    public String getAccessKey(String provider, String code, HttpServletResponse response) throws JsonProcessingException {
        Map<String ,String> providers = new HashMap<>();
        providers.put("providerUrl",provider == "KAKAO" ? KAKAO_TOKEN_URL_HOST : LINE_TOKEN_URL_HOST );
        providers.put("clientId",provider == "KAKAO" ? kakaoClientId : lineClientId);
        providers.put("clientSecret",provider == "KAKAO" ? kakaoSecretKey : lineSecretKey);

        MultiValueMap<String , String > params= new LinkedMultiValueMap<>();

        params.add("client_id", providers.get("clientId"));
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", "http://localhost:8080/api/social/"+provider.toLowerCase()+"/callback");
        params.add("client_secret",providers.get("clientSecret"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(providers.get("providerUrl"), request, String.class);


        String responseBody = responseEntity.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        System.out.println("accessToken : "+jsonNode.get("access_token").asText());

        return jsonNode.get("access_token").asText();

    }

    public SocialMemberInfoDto getMemberInfo(String provider ,String accessToken) throws JsonProcessingException {


        // TODO : 구글까지온다면?
        String profile = provider =="KAKAO" ?"https://kapi.kakao.com/v2/user/me" : "https://api.line.me/v2/profile";
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);

        HttpEntity<String> request = new HttpEntity<>(headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(profile, request, String.class);

        String responseBody = responseEntity.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        if(provider =="KAKAO"){
            return SocialMemberInfoDto.builder()
                    .nickname(jsonNode.get("properties").get("nickname").asText())
                    .profileImage(jsonNode.get("properties").get("profile_image").asText())
                    .email(jsonNode.get("properties").get("email").asText()+"@kakao.com")
                    .build();

        }else{
            return SocialMemberInfoDto.builder()
                    .nickname(jsonNode.get("displayName").asText())
                    .profileImage("profile_image")
                    .email(jsonNode.get("userId").asText() + "@line.com")
                    .build();
        }

    }

    private Member registerMember(String provider ,SocialMemberInfoDto socialMemberInfoDto) {
        Optional<Member> member = memberRepository.findByEmail(socialMemberInfoDto.getEmail());

        Member fMember = null;
        String nickname = socialMemberInfoDto.getNickname();
        if(memberRepository.existsByNickname(nickname)){
            Long duplicateMember = memberRepository.countByNickname(nickname);
            nickname+=duplicateMember;
        }
        if (member.isEmpty()) {
            fMember = Member.builder()
                    .memberId(socialMemberInfoDto.getNickname()+UUID.randomUUID().toString())
                    .email(socialMemberInfoDto.getEmail())
                    .password(UUID.randomUUID().toString())
                    .providerType(provider =="KAKAO" ? ProviderType.KAKAO: ProviderType.LINE)
                    .nickname(nickname)
                    .build();
            memberRepository.save(fMember);
        }
        return fMember;
    }

    private Authentication forceLogin(Member member) {

        CustomUserDetails userDetails = new CustomUserDetails(member);

        Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        SecurityContextHolder.getContext().setAuthentication(authentication);

        return authentication;

    }
}
